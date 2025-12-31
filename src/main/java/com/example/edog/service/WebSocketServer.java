package com.example.edog.service;

import com.example.edog.utils.AliyunCredentials;
import com.example.edog.utils.AudioConverter;
import com.example.edog.utils.CozeAPI;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final Map<String, Timer> sessionTimers = new ConcurrentHashMap<>(); 
    private final Map<String, AtomicBoolean> sessionBusyState = new ConcurrentHashMap<>();
    
    // 记录最后一次发送给 ASR 数据的时间戳
    private final Map<String, Long> lastAsrSendTime = new ConcurrentHashMap<>();

    // 存储所有活跃会话，用于广播控制指令
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    private final CozeAPI cozeAPI = new CozeAPI();

    private static volatile String currentVoiceId = "7568423452617523254";
    private static volatile Double currentSpeedRatio = 1.0;
    private static volatile int currentVolume = 70;

    // Opus 静音帧
    private static final byte[] OPUS_SILENCE_FRAME = new byte[]{(byte) 0xF8, (byte) 0xFF, (byte) 0xFE};

    public static void setVoiceParams(String voiceId, Double speedRatio) {
        setVoiceParams(voiceId, speedRatio, null);
    }

    public static void setVoiceParams(String voiceId, Double speedRatio, Integer volume) {
        if (voiceId != null && !voiceId.isEmpty()) currentVoiceId = voiceId;
        if (speedRatio != null) currentSpeedRatio = speedRatio;
        if (volume != null) currentVolume = volume;
        log.info("语音参数已全局更新: voiceId={}, speed={}, volume={}", currentVoiceId, currentSpeedRatio, currentVolume);
    }

    @Override
    public void afterConnectionEstablished(@NotNull WebSocketSession session) throws Exception {
        String id = session.getId();
        log.info("ESP32 Connected: {}", id);
        
        activeSessions.put(id, session);

        sessionBusyState.put(id, new AtomicBoolean(false));
        lastAsrSendTime.put(id, System.currentTimeMillis()); 

        AliyunRealtimeASR asr = new AliyunRealtimeASR(credentials.getAppKey());
        asr.setOnResultCallback(text -> {
            if (isSessionBusy(id)) return;
            handleAsrText(session, id, text);
        });

        try {
            String token = tokenService.getToken();
            asr.start(token);
            asrServices.put(id, asr);
        } catch (Exception e) {
            log.error("ASR 启动失败", e);
            session.close();
            return;
        }

        // 启动定时任务
        Timer timer = new Timer(true);
        
        // 1. WebSocket 心跳
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    if (session.isOpen()) session.sendMessage(new PingMessage());
                } catch (Exception e) {}
            }
        }, 5000, 5000);

        // 2. ASR 保活任务
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    AliyunRealtimeASR currentAsr = asrServices.get(id);
                    if (currentAsr == null || !session.isOpen()) return;

                    long lastTime = lastAsrSendTime.getOrDefault(id, 0L);
                    long now = System.currentTimeMillis();

                    if (now - lastTime > 800) {
                        currentAsr.sendOpusStream(OPUS_SILENCE_FRAME);
                        lastAsrSendTime.put(id, now);
                    }
                } catch (Exception e) {
                    log.error("保活帧发送失败", e);
                }
            }
        }, 1000, 500); 

        sessionTimers.put(id, timer);
    }

    @Override
    protected void handleBinaryMessage(@NotNull WebSocketSession session, @NotNull BinaryMessage message) {
        String id = session.getId();
        AliyunRealtimeASR asr = asrServices.get(id);

        if (asr != null) {
            try {
                lastAsrSendTime.put(id, System.currentTimeMillis());

                if (isSessionBusy(id)) {
                    asr.sendOpusStream(OPUS_SILENCE_FRAME);
                } else {
                    ByteBuffer payload = message.getPayload();
                    byte[] opusData = new byte[payload.remaining()];
                    payload.get(opusData);
                    asr.sendOpusStream(opusData);
                }
            } catch (Exception e) {
                log.error("ASR 发送异常, 尝试重置", e);
                resetAsr(session, id, asr);
            }
        }
    }

    /**
     * 处理 ASR 文本，直接触发对话（已移除唤醒词逻辑）。
     */
    private void handleAsrText(WebSocketSession session, String sessionId, String text) {
        if (text == null || text.trim().isEmpty()) return;

        String question = text.trim();
        log.info("收到语音识别结果: {}", question);

        handleUserQuestion(session, question);
    }

    private void resetAsr(WebSocketSession session, String id, AliyunRealtimeASR oldAsr) {
        if (oldAsr != null) oldAsr.stop();
        AliyunRealtimeASR newAsr = new AliyunRealtimeASR(credentials.getAppKey());
        newAsr.setOnResultCallback(text -> {
            if (!isSessionBusy(id)) handleUserQuestion(session, text);
        });
        try {
            newAsr.start(tokenService.getToken());
            asrServices.put(id, newAsr);
            log.info("ASR 引擎已自动恢复");
        } catch (Exception ex) {
            log.error("ASR 恢复失败", ex);
        }
    }

    private void handleUserQuestion(WebSocketSession session, String question) {
        String id = session.getId();
        setSessionBusy(id, true);

        new Thread(() -> {
            try {
                String shouldUseVoiceId = currentVoiceId;
                Double shouldUseSpeed = currentSpeedRatio;

                log.info("请求智能体: '{}' (Locking session)", question);

                String[] response = cozeAPI.CozeRequest(question, shouldUseVoiceId, shouldUseSpeed, true);

                if (response != null && response.length >= 2) {
                    String audioUrl = response[0];
                    String replyText = response[1];
                    log.info("智能体文本回复（仅日志记录，不下发设备）: {}", replyText);

                    if (!session.isOpen()) {
                        setSessionBusy(id, false);
                        return;
                    }

                    // 1. 发送开始标志（不包含文本内容）
                    session.sendMessage(new TextMessage("{\"type\":\"tts\",\"state\":\"start\"}"));

                    // 2. 在请求阶段即发送当前音量（纯数字）
                    session.sendMessage(new TextMessage(String.valueOf(currentVolume)));

                    // 3. 发送音频
                    if (replyText != null && !replyText.isEmpty()) {
                        log.info("开始阿里云 TTS 合成: {}", replyText);
                        
                        BlockingQueue<byte[]> frameQueue = new LinkedBlockingQueue<>();
                        AtomicBoolean isComplete = new AtomicBoolean(false);

                        // 异步启动 TTS
                        CompletableFuture.runAsync(() -> {
                            ttsService.synthesizeStream(replyText, frameQueue::offer, () -> isComplete.set(true));
                        });

                        // 消费队列并发送
                        while (!isComplete.get() || !frameQueue.isEmpty()) {
                            byte[] frame = frameQueue.poll(100, TimeUnit.MILLISECONDS);
                            if (frame != null) {
                                if (session.isOpen()) {
                                    session.sendMessage(new BinaryMessage(frame));
                                    lastAsrSendTime.put(id, System.currentTimeMillis());
                                    // 60ms 一帧，稍微控制发送节奏
                                    try { Thread.sleep(55); } catch (InterruptedException ignored) {}
                                } else {
                                    break;
                                }
                            }
                        }
                    }

                    // 4. 发送结束标志
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage("{\"type\":\"tts\",\"state\":\"end\"}"));
                    }

                    // 估算播放时间，用于延迟解锁
                    // 假设文本字数 * 250ms/字，或直接固定一个延迟
                    long unlockDelay = 500; // 简单给 500ms 缓冲
                    log.info("发送完毕，将在 {} ms 后解锁输入", unlockDelay);

                    new Timer().schedule(new TimerTask() {
                        @Override
                        public void run() {
                            setSessionBusy(id, false);
                            log.info("会话已解锁，准备接收新语音");
                        }
                    }, unlockDelay);

                } else {
                    setSessionBusy(id, false);
                }
            } catch (Exception e) {
                log.error("处理失败", e);
                setSessionBusy(id, false);
            }
        }).start();
    }

    private boolean isSessionBusy(String sessionId) {
        AtomicBoolean state = sessionBusyState.get(sessionId);
        return state != null && state.get();
    }

    private void setSessionBusy(String sessionId, boolean busy) {
        AtomicBoolean state = sessionBusyState.get(sessionId);
        if (state != null) {
            state.set(busy);
        }
    }

    @Override
    public void afterConnectionClosed(@NotNull WebSocketSession session, @NotNull CloseStatus status) {
        String id = session.getId();
        log.info("ESP32 Disconnected: {}", id);

        activeSessions.remove(id);

        AliyunRealtimeASR asr = asrServices.remove(id);
        if (asr != null) asr.stop();

        Timer timer = sessionTimers.remove(id);
        if (timer != null) timer.cancel();

        sessionBusyState.remove(id);
        lastAsrSendTime.remove(id);
    }

    @Override
    public void handleTransportError(@NotNull WebSocketSession session, @NotNull Throwable exception) {
        afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    /**
     * 广播发送控制指令给所有连接的设备
     */
    public void broadcastControlMessage(String message) {
        log.info("广播控制指令: {}", message);
        activeSessions.values().forEach(session -> {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (Exception e) {
                    log.error("向会话 {} 发送广播失败", session.getId(), e);
                }
            }
        });
    }
}
