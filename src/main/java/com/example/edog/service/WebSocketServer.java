package com.example.edog.service;

import com.example.edog.enums.ControlCommandEnum;
import com.example.edog.utils.AliyunCredentials;
import com.example.edog.utils.CozeAPI;
import com.example.edog.utils.DeviceProtocolParser;
import com.example.edog.utils.WakeWordUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class WebSocketServer extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WebSocketServer.class);

    @Autowired
    private AliyunTokenService tokenService;

    @Autowired
    private AliyunCredentials credentials;

    @Autowired
    private AliyunTTSService ttsService;

    private final Map<String, AliyunRealtimeASR> asrServices = new ConcurrentHashMap<>();
    
    // 使用全局调度池处理定时任务（心跳、ASR检查）
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    
    // 核心业务线程池：用于处理唤醒、指令解析、AI对话等耗时操作，替代 new Thread().start()
    private final ExecutorService workerExecutor = Executors.newCachedThreadPool();
    
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    // 设备标识映射，用于检测同一设备重连（通过远程地址或设备ID）
    private final Map<String, String> deviceToSessionMap = new ConcurrentHashMap<>();

    private static final long TTS_SILENCE_PERIOD_MS = 1500; // TTS结束后1.5秒内忽略ASR结果

    // 唤醒词机制相关
    private static final long AWAKE_TIMEOUT_MS = 30000; // 唤醒后30秒无响应则自动休眠
    private static final String WAKE_RESPONSE = "我在呢"; // 唤醒词响应

    private static final class SessionState {
        private final AtomicBoolean busy = new AtomicBoolean(false);
        private final AtomicBoolean resettingAsr = new AtomicBoolean(false);
        private final AtomicBoolean awake = new AtomicBoolean(false);
        private volatile long lastAsrSendTime;
        private volatile long lastPongTime;
        private volatile long ttsEndTime;
        private volatile long awakeTime;
        private volatile long lastAsrResultTime;  // ASR 最后一次返回结果的时间
        private volatile long audioFrameCount;     // 累计接收的音频帧数
        private volatile List<ScheduledFuture<?>> tasks;
        // 用于拼接分片消息的缓冲区
        private final java.io.ByteArrayOutputStream fragmentBuffer = new java.io.ByteArrayOutputStream();
        
        // 独立的语音配置
        private volatile String voiceId;
        private volatile Double speedRatio;
        private volatile int volume;
        private volatile String conversationId; // Coze 会话ID
        
        public SessionState(String defaultVoiceId, Double defaultSpeed, int defaultVolume) {
            this.voiceId = defaultVoiceId;
            this.speedRatio = defaultSpeed;
            this.volume = defaultVolume;
        }
    }

    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();

    private final CozeAPI cozeAPI = new CozeAPI();

    // 默认语音配置（用于新连接的会话）
    private volatile String defaultVoiceId = "7568423452617523254";
    private volatile Double defaultSpeedRatio = 1.0;
    private volatile int defaultVolume = 70;

    // Opus 静音帧
    private static final byte[] OPUS_SILENCE_FRAME = new byte[]{(byte) 0xF8, (byte) 0xFF, (byte) 0xFE};

    private final AtomicReference<String> latestLt = new AtomicReference<>("(0,0)");
    private final AtomicInteger latestVolume = new AtomicInteger(0);

    /**
     * 设置语音参数
     * 更新默认配置，并同步更新所有活跃会话的配置
     */
    public void setVoiceParams(String voiceId, Double speedRatio, Integer volume) {
        // 更新默认配置
        if (voiceId != null && !voiceId.isEmpty()) this.defaultVoiceId = voiceId;
        if (speedRatio != null) this.defaultSpeedRatio = speedRatio;
        if (volume != null) this.defaultVolume = volume;
        
        log.info("全局语音参数已更新，正在同步至活跃会话: voiceId={}, speed={}, volume={}", 
                defaultVoiceId, defaultSpeedRatio, defaultVolume);
        
        // 更新所有活跃会话的配置
        sessionStates.values().forEach(state -> {
            if (voiceId != null && !voiceId.isEmpty()) state.voiceId = voiceId;
            if (speedRatio != null) state.speedRatio = speedRatio;
            if (volume != null) state.volume = volume;
        });
    }
    
    // 兼容旧接口（如果有调用）
    public void setVoiceParams(String voiceId, Double speedRatio) {
        setVoiceParams(voiceId, speedRatio, null);
    }

    private void registerSession(WebSocketSession session, String sessionId) {
        activeSessions.put(sessionId, session);
        // 使用当前默认配置初始化会话状态
        SessionState state = new SessionState(defaultVoiceId, defaultSpeedRatio, defaultVolume);
        
        long now = System.currentTimeMillis();
        state.lastAsrSendTime = now;
        state.lastPongTime = now;
        state.ttsEndTime = 0;
        state.awakeTime = 0;
        state.lastAsrResultTime = now;
        state.audioFrameCount = 0;
        sessionStates.put(sessionId, state);
    }

    private List<ScheduledFuture<?>> scheduleSessionTasks(WebSocketSession session, String sessionId) {
        SessionState state = sessionStates.get(sessionId);
        if (state == null) return new ArrayList<>();

        List<ScheduledFuture<?>> tasks = new ArrayList<>();

        tasks.add(scheduler.scheduleAtFixedRate(() -> {
            try {
                if (session.isOpen()) session.sendMessage(new PingMessage());
            } catch (Exception e) {
                log.warn("心跳Ping发送失败: session={}, error={}", sessionId, e.getMessage());
            }
        }, 5, 10, TimeUnit.SECONDS));

        tasks.add(scheduler.scheduleAtFixedRate(() -> {
            if (!session.isOpen() || !activeSessions.containsKey(sessionId)) return;
            AliyunRealtimeASR currentAsr = asrServices.get(sessionId);
            if (currentAsr == null || !currentAsr.isRunning()) return;

            long now = System.currentTimeMillis();
            if (now - state.lastAsrSendTime <= 800) return;

            try {
                currentAsr.sendOpusStream(OPUS_SILENCE_FRAME);
                state.lastAsrSendTime = now;
            } catch (Exception e) {
                log.debug("静音帧发送异常（忽略）: {}", e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS));

        // ASR 健康检查任务
        tasks.add(scheduler.scheduleAtFixedRate(() -> {
            if (!session.isOpen() || !activeSessions.containsKey(sessionId)) return;
            if (isSessionBusy(sessionId)) return;
            
            AliyunRealtimeASR currentAsr = asrServices.get(sessionId);
            if (currentAsr == null) return;
            
            long now = System.currentTimeMillis();
            long frameCount = state.audioFrameCount;
            long lastResultTime = state.lastAsrResultTime;
            
            boolean asrUnhealthy = !currentAsr.isHealthy();
            boolean expectingSpeech = state.awake.get() && (now - state.awakeTime) <= AWAKE_TIMEOUT_MS;
            boolean noResultsForLong = expectingSpeech && frameCount > 500 && (now - lastResultTime) > 60000;
            
            if (asrUnhealthy || noResultsForLong) {
                if (asrUnhealthy) {
                    log.warn("ASR 健康检查失败(发送失败): session={}, sendFailCount={}", sessionId, currentAsr.getSendFailCount());
                } else {
                    log.warn("ASR 健康检查失败(无响应): session={}, frameCount={}, lastResultAge={}ms", sessionId, frameCount, now - lastResultTime);
                }
                
                state.audioFrameCount = 0;
                state.lastAsrResultTime = now;
                
                if (state.resettingAsr.compareAndSet(false, true)) {
                    // 使用 workerExecutor 处理重置操作
                    workerExecutor.submit(() -> {
                        try {
                            resetAsr(session, sessionId, currentAsr);
                        } finally {
                            state.resettingAsr.set(false);
                        }
                    });
                }
            }
        }, 30, 30, TimeUnit.SECONDS));

        state.tasks = tasks;
        return tasks;
    }

    @Override
    public void afterConnectionEstablished(@NotNull WebSocketSession session) throws Exception {
        String id = session.getId();
        log.info("ESP32 Connected: {}", id);

        String deviceKey = getDeviceKey(session);
        String oldSessionId = deviceToSessionMap.get(deviceKey);
        if (oldSessionId != null && !oldSessionId.equals(id)) {
            log.warn("检测到设备重连，清理旧会话: oldSession={}, newSession={}, device={}", oldSessionId, id, deviceKey);
            cleanupSession(oldSessionId, "设备重连");
        }

        deviceToSessionMap.put(deviceKey, id);
        registerSession(session, id);

        // 异步创建 Coze 会话
        workerExecutor.submit(() -> {
            String convId = cozeAPI.createConversation();
            if (convId != null) {
                SessionState state = sessionStates.get(id);
                if (state != null) state.conversationId = convId;
            }
        });

        try {
            AliyunRealtimeASR asr = startAsrForSession(session, id);
            if (asr != null) {
                log.info("ASR 初始启动成功: {}", id);
            }
        } catch (Exception e) {
            log.error("ASR 初始启动失败: {}", e.getMessage());
        }

        scheduleSessionTasks(session, id);
    }

    @Override
    protected void handleBinaryMessage(@NotNull WebSocketSession session, @NotNull BinaryMessage message) {
        String id = session.getId();
        SessionState state = sessionStates.get(id);
        if (state == null) return;

        try {
            ByteBuffer payload = message.getPayload();
            byte[] data = new byte[payload.remaining()];
            payload.get(data);

            state.fragmentBuffer.write(data);

            if (!message.isLast()) return;

            byte[] opusData = state.fragmentBuffer.toByteArray();
            state.fragmentBuffer.reset();

            if (opusData.length == 0) return;

            state.lastAsrSendTime = System.currentTimeMillis();
            state.audioFrameCount++;

            AliyunRealtimeASR asr = asrServices.get(id);
            if (asr == null || !asr.isRunning()) {
                ensureAsrReady(session, id);
                return;
            }

            if (isSessionBusy(id)) {
                asr.sendOpusStream(OPUS_SILENCE_FRAME);
            } else {
                asr.sendOpusStream(opusData);
            }
        } catch (Exception e) {
            log.debug("处理音频数据时出现异常（连接保持）: {}", e.getMessage());
            try { state.fragmentBuffer.reset(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void handleTextMessage(@NotNull WebSocketSession session, @NotNull TextMessage message) {
        String payload = message.getPayload();
        if (payload == null) return;
        String text = payload.trim();

        // 使用 DeviceProtocolParser 解析协议
        Integer volume = DeviceProtocolParser.parseVolume(text);
        if (volume != null) {
            latestVolume.set(volume);
            return;
        }

        String lightStatus = DeviceProtocolParser.parseLightStatus(text);
        if (lightStatus != null) {
            latestLt.set(lightStatus);
        }
    }

    private void sendTtsStream(WebSocketSession session, String text) throws Exception {
        if (text == null || text.isEmpty()) return;
        String id = session.getId();
        log.info("开始阿里云 TTS 合成: {}", text);

        BlockingQueue<byte[]> frameQueue = new LinkedBlockingQueue<>();
        AtomicBoolean isComplete = new AtomicBoolean(false);
        AtomicBoolean hasStarted = new AtomicBoolean(false);

        CompletableFuture.runAsync(() -> {
            ttsService.synthesizeStream(text, frame -> {
                hasStarted.set(true);
                frameQueue.offer(frame);
            }, () -> isComplete.set(true));
        });

        int waitCount = 0;
        while (!hasStarted.get() && waitCount < 30) {
            Thread.sleep(100);
            waitCount++;
        }

        int preBufferMs = text.length() <= 5 ? 50 : 100;
        Thread.sleep(preBufferMs);

        while (!isComplete.get() || !frameQueue.isEmpty()) {
            byte[] frame = frameQueue.poll(50, TimeUnit.MILLISECONDS);
            if (frame != null) {
                if (session.isOpen()) {
                    session.sendMessage(new BinaryMessage(frame));
                    SessionState state = sessionStates.get(id);
                    if (state != null) state.lastAsrSendTime = System.currentTimeMillis();
                    Thread.sleep(45);
                } else {
                    break;
                }
            }
        }
    }

    private void playTts(WebSocketSession session, String sessionId, String text) throws Exception {
        if (text == null || text.isEmpty()) return;
        if (!session.isOpen() || !activeSessions.containsKey(sessionId)) return;
        session.sendMessage(new TextMessage("{\"type\":\"tts\",\"state\":\"start\"}"));
        sendTtsStream(session, text);
        if (session.isOpen()) {
            session.sendMessage(new TextMessage("{\"type\":\"tts\",\"state\":\"end\"}"));
        }
        SessionState state = sessionStates.get(sessionId);
        if (state != null) state.ttsEndTime = System.currentTimeMillis();
    }

    private void handleAsrText(WebSocketSession session, String sessionId, String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (!validateSession(sessionId, session)) return;

        SessionState state = sessionStates.get(sessionId);
        if (state == null) return;

        state.lastAsrResultTime = System.currentTimeMillis();
        state.audioFrameCount = 0;

        if (isInTtsSilencePeriod(state, text)) return;

        String question = text.trim();
        log.info("收到语音识别结果: {}", question);

        String pinyin = WakeWordUtils.convertToPinyin(question);
        String cleanPinyin = pinyin.replaceAll("[^a-z]", "");

        if (WakeWordUtils.isWakeWord(cleanPinyin)) {
            log.info("触发唤醒词: 小灵 (pinyin: {})", cleanPinyin);
            handleWakeUp(session, sessionId);
            return;
        }

        if (!state.awake.get()) {
            log.debug("会话未唤醒，忽略ASR结果: {}", question);
            return;
        }

        if (System.currentTimeMillis() - state.awakeTime > AWAKE_TIMEOUT_MS) {
            log.info("唤醒已超时，进入休眠状态");
            setSessionAwake(sessionId, false);
            return;
        }

        log.info("处理唤醒后的用户问题: {}", question);
        handleUserQuestion(session, sessionId, question);
    }

    private boolean validateSession(String sessionId, WebSocketSession session) {
        if (!activeSessions.containsKey(sessionId) || !session.isOpen()) {
            log.debug("会话已关闭，忽略ASR结果: {}", sessionId);
            return false;
        }
        return true;
    }

    private boolean isInTtsSilencePeriod(SessionState state, String text) {
        long elapsed = System.currentTimeMillis() - state.ttsEndTime;
        if (elapsed < TTS_SILENCE_PERIOD_MS) {
            log.debug("忽略静默期内的ASR结果 ({}ms): {}", elapsed, text.trim());
            return true;
        }
        return false;
    }

    private void handleWakeUp(WebSocketSession session, String sessionId) {
        if (isSessionBusy(sessionId)) return;

        SessionState state = sessionStates.get(sessionId);
        if (state == null) return;
        
        if (!state.busy.compareAndSet(false, true)) return;

        // 使用线程池执行唤醒响应
        workerExecutor.submit(() -> {
            try {
                if (!validateSession(sessionId, session)) return;

                log.info("播放唤醒响应: {}", WAKE_RESPONSE);
                playTts(session, sessionId, WAKE_RESPONSE);

                setSessionAwake(sessionId, true);
                state.awakeTime = System.currentTimeMillis();

                log.info("会话已唤醒，等待用户指令: {}", sessionId);
                Thread.sleep(300);

            } catch (Exception e) {
                log.error("唤醒处理失败", e);
            } finally {
                setSessionBusy(sessionId, false);
            }
        });
    }

    private void setSessionAwake(String sessionId, boolean awake) {
        SessionState state = sessionStates.get(sessionId);
        if (state == null) return;
        state.awake.set(awake);
        log.info("会话唤醒状态变更: {} -> {}", sessionId, awake ? "已唤醒" : "休眠");
    }

    private void handleUserQuestion(WebSocketSession session, String sessionId, String question) {
        if (!validateSession(sessionId, session)) return;

        SessionState state = sessionStates.get(sessionId);
        if (state == null) return;
        
        if (!state.busy.compareAndSet(false, true)) {
            log.warn("handleUserQuestion: 会话忙碌中或状态异常，跳过: {}", sessionId);
            return;
        }

        // 使用线程池执行业务逻辑
        workerExecutor.submit(() -> {
            try {
                if (!validateSession(sessionId, session)) {
                    setSessionBusy(sessionId, false);
                    return;
                }

                ControlCommandEnum command = ControlCommandEnum.match(question);
                if (command != null) {
                    processControlCommand(session, sessionId, command, question);
                    return;
                }

                processAiChat(session, sessionId, question);

            } catch (Exception e) {
                log.error("处理失败", e);
                resetSessionState(sessionId);
            }
        });
    }

    private void processControlCommand(WebSocketSession session, String sessionId, ControlCommandEnum command, String question) throws Exception {
        log.info("检测到控制命令: {}, 原问题: {}", command.getCommand(), question);
        
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(command.getCommand()));
            log.info("已发送控制命令到设备: {}", command.getCommand());
        }

        String confirmText = command.getConfirmText();
        if (confirmText != null) {
            playTts(session, sessionId, confirmText);
        }

        Thread.sleep(800);
        resetSessionState(sessionId);
        log.info("控制命令处理完成，会话已解锁");
    }

    private void processAiChat(WebSocketSession session, String sessionId, String question) throws Exception {
        SessionState state = sessionStates.get(sessionId);
        // 使用会话独立的语音配置
        String shouldUseVoiceId = state != null ? state.voiceId : defaultVoiceId;
        Double shouldUseSpeed = state != null ? state.speedRatio : defaultSpeedRatio;
        String conversationId = state != null ? state.conversationId : null;

        log.info("请求智能体: '{}' (Voice: {}, Speed: {}, ConvId: {})", question, shouldUseVoiceId, shouldUseSpeed, conversationId);
        String[] response = cozeAPI.CozeRequest(question, shouldUseVoiceId, shouldUseSpeed, true, conversationId);

        if (response != null && response.length >= 2) {
            String replyText = response[1];
            log.info("智能体文本回复: {}", replyText);

            if (!session.isOpen()) {
                setSessionBusy(sessionId, false);
                return;
            }

            playTts(session, sessionId, replyText);

            scheduler.schedule(() -> {
                resetSessionState(sessionId);
                log.info("会话已解锁并进入休眠状态，等待唤醒词");
            }, 1200, TimeUnit.MILLISECONDS);

        } else {
            resetSessionState(sessionId);
        }
    }

    private void resetSessionState(String sessionId) {
        setSessionAwake(sessionId, false);
        setSessionBusy(sessionId, false);
    }

    private boolean isSessionBusy(String sessionId) {
        SessionState state = sessionStates.get(sessionId);
        return state != null && state.busy.get();
    }

    private void setSessionBusy(String sessionId, boolean busy) {
        SessionState state = sessionStates.get(sessionId);
        if (state != null) state.busy.set(busy);
    }

    private AliyunRealtimeASR startAsrForSession(WebSocketSession session, String sessionId) {
        if (!session.isOpen() || !activeSessions.containsKey(sessionId)) return null;

        AliyunRealtimeASR asr = new AliyunRealtimeASR(credentials.getAppKey());
        asr.setOnResultCallback(text -> {
            if (!activeSessions.containsKey(sessionId)) return;
            AliyunRealtimeASR currentAsr = asrServices.get(sessionId);
            if (currentAsr == null || currentAsr != asr) return;
            if (isSessionBusy(sessionId)) return;
            handleAsrText(session, sessionId, text);
        });

        asr.setOnActivityCallback(() -> {
            if (!activeSessions.containsKey(sessionId)) return;
            SessionState state = sessionStates.get(sessionId);
            if (state != null) {
                state.lastAsrResultTime = System.currentTimeMillis();
            }
        });

        asrServices.put(sessionId, asr);
        try {
            String token = tokenService.getToken();
            asr.start(token);
        } catch (Exception e) {
            asrServices.remove(sessionId, asr);
            try { asr.forceStop(); } catch (Exception ignored) {}
            throw e;
        }

        if (!session.isOpen() || !activeSessions.containsKey(sessionId)) {
            asrServices.remove(sessionId, asr);
            asr.forceStop();
            return null;
        }

        return asr;
    }

    private void resetAsr(WebSocketSession session, String id, AliyunRealtimeASR oldAsr) {
        log.info("正在重置 ASR 会话: {}", id);
        asrServices.remove(id);

        if (oldAsr != null) {
            try {
                oldAsr.forceStop();
            } catch (Exception e) {
                log.warn("强制停止旧 ASR 异常: {}", e.getMessage());
            }
        }

        if (!session.isOpen()) return;

        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return;
        }

        if (!session.isOpen()) return;

        try {
            AliyunRealtimeASR asr = startAsrForSession(session, id);
            if (asr != null) log.info("ASR 重置成功: {}", id);
        } catch (Exception e) {
            log.error("ASR 重置启动失败: {}", e.getMessage());
        }
    }

    private void ensureAsrReady(WebSocketSession session, String id) {
        if (!session.isOpen() || !activeSessions.containsKey(id)) return;
        SessionState state = sessionStates.get(id);
        if (state == null) return;
        
        if (!state.resettingAsr.compareAndSet(false, true)) return;
        
        workerExecutor.submit(() -> {
            try {
                AliyunRealtimeASR oldAsr = asrServices.get(id);
                resetAsr(session, id, oldAsr);
            } finally {
                state.resettingAsr.set(false);
            }
        });
    }

    @Override
    public void afterConnectionClosed(@NotNull WebSocketSession session, @NotNull CloseStatus status) {
        String id = session.getId();
        log.info("ESP32 Disconnected: id={}, code={}, reason={}", id, status.getCode(), status.getReason());
        cleanupSession(id, "连接关闭: code=" + status.getCode());
    }

    private String getDeviceKey(WebSocketSession session) {
        try {
            if (session.getRemoteAddress() != null) {
                return session.getRemoteAddress().toString();
            }
        } catch (Exception e) {
            log.debug("获取远程地址失败: {}", e.getMessage());
        }
        return session.getId();
    }

    private synchronized void cleanupSession(String sessionId, String reason) {
        log.info("开始清理会话: id={}, reason={}", sessionId, reason);

        if (!activeSessions.containsKey(sessionId) && !asrServices.containsKey(sessionId)) return;

        SessionState state = sessionStates.remove(sessionId);
        if (state != null) {
            if (state.tasks != null) state.tasks.forEach(t -> t.cancel(false));
            try { state.fragmentBuffer.reset(); } catch (Exception ignored) {}
        }

        WebSocketSession session = activeSessions.remove(sessionId);
        if (session != null) {
            deviceToSessionMap.remove(getDeviceKey(session), sessionId);
        }

        AliyunRealtimeASR asr = asrServices.remove(sessionId);
        if (asr != null) {
            CompletableFuture.runAsync(() -> {
                try { asr.forceStop(); } catch (Exception ignored) {}
            });
        }
    }

    @Override
    protected void handlePongMessage(@NotNull WebSocketSession session, @NotNull PongMessage message) {
        String id = session.getId();
        SessionState state = sessionStates.get(id);
        if (state != null) state.lastPongTime = System.currentTimeMillis();
    }

    @Override
    public void handleTransportError(@NotNull WebSocketSession session, @NotNull Throwable exception) {
        String id = session.getId();
        log.warn("WebSocket 传输错误: id={}, error={}", id, exception.getMessage());
    }
    
    public void broadcastControlMessage(String message) {
        if (message == null || message.isEmpty()) return;
        activeSessions.values().forEach(session -> {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (Exception e) {
                    log.error("广播消息失败: {}", e.getMessage());
                }
            }
        });
    }

    public String getLatestLt() {
        return latestLt.get();
    }

    public int getLatestVolume() {
        return latestVolume.get();
    }
}
