package com.example.edog.service;

import com.example.edog.utils.AliyunCredentials;
import com.example.edog.utils.CozeAPI;
import net.sourceforge.pinyin4j.PinyinHelper;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;

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
    // 使用全局调度池替代每个会话一个Timer，减少资源消耗
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    // 设备标识映射，用于检测同一设备重连（通过远程地址或设备ID）
    private final Map<String, String> deviceToSessionMap = new ConcurrentHashMap<>();

    private static final long TTS_SILENCE_PERIOD_MS = 1500; // TTS结束后1.5秒内忽略ASR结果

    // 唤醒词机制相关
    private static final long AWAKE_TIMEOUT_MS = 30000; // 唤醒后30秒无响应则自动休眠
    private static final String WAKE_WORD_PINYIN = "xiaoaitongxue"; // 唤醒词拼音
    private static final String WAKE_RESPONSE = "我在呢"; // 唤醒词响应
    private static final double WAKE_WORD_SIMILARITY_THRESHOLD = 0.75; // 唤醒词相似度阈值

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
    }

    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();

    private final CozeAPI cozeAPI = new CozeAPI();

    private static volatile String currentVoiceId = "7568423452617523254";
    private static volatile Double currentSpeedRatio = 1.0;
    private static volatile int currentVolume = 70;

    // Opus 静音帧
    private static final byte[] OPUS_SILENCE_FRAME = new byte[]{(byte) 0xF8, (byte) 0xFF, (byte) 0xFE};

    private static final int MAX_TEMPERATURE_K = 6500;
    private static final Pattern LT_PATTERN = Pattern.compile("^\\((\\d+)\\s*[，,]\\s*(\\d+)\\)$");
    private final AtomicReference<String> latestLt = new AtomicReference<>("(0,0)");

    private static final Pattern VOLUME_PATTERN = Pattern.compile("^\\((?i:volume|vol)\\s*[，,]\\s*(\\d{1,3})\\)$");
    private static final Pattern VOLUME_JSON_PATTERN = Pattern.compile("\"volume\"\\s*:\\s*(\\d{1,3})");
    private final AtomicInteger latestVolume = new AtomicInteger(0);

    // 用户语音命令检测模式（支持中英文）
    private static final Pattern CMD_VOLUME_UP = Pattern.compile(
            "(调高|提高|增大|加大|升高|大一?点|大声|音量.*[加增高大升]|volume.*up|louder|increase.*volume)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CMD_VOLUME_DOWN = Pattern.compile(
            "(调低|降低|减小|减少|小一?点|小声|音量.*[减降低小]|volume.*down|quieter|decrease.*volume)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CMD_BRIGHTNESS_UP = Pattern.compile(
            "(调亮|提亮|增亮|亮一?点|亮度.*[加增高大升亮]|brighter|brightness.*up)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CMD_BRIGHTNESS_DOWN = Pattern.compile(
            "(调暗|降暗|暗一?点|亮度.*[减降低小暗]|dimmer|brightness.*down)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CMD_TEMP_UP = Pattern.compile(
            "(调暖|变暖|暖一?点|暖色|色温.*[加增高大升暖]|warmer|temperature.*up)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CMD_TEMP_DOWN = Pattern.compile(
            "(调冷|变冷|冷一?点|冷色|色温.*[减降低小冷]|cooler|temperature.*down)",
            Pattern.CASE_INSENSITIVE);

    public static void setVoiceParams(String voiceId, Double speedRatio) {
        setVoiceParams(voiceId, speedRatio, null);
    }

    public static void setVoiceParams(String voiceId, Double speedRatio, Integer volume) {
        if (voiceId != null && !voiceId.isEmpty()) currentVoiceId = voiceId;
        if (speedRatio != null) currentSpeedRatio = speedRatio;
        if (volume != null) currentVolume = volume;
        log.info("语音参数已全局更新: voiceId={}, speed={}, volume={}", currentVoiceId, currentSpeedRatio, currentVolume);
    }

    private void registerSession(WebSocketSession session, String sessionId) {
        activeSessions.put(sessionId, session);
        SessionState state = new SessionState();
        long now = System.currentTimeMillis();
        state.lastAsrSendTime = now;
        state.lastPongTime = now;
        state.ttsEndTime = 0;
        state.awakeTime = 0;
        state.lastAsrResultTime = now;  // 初始化为当前时间
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
        }, 5, 10, TimeUnit.SECONDS)); // 每10秒发送一次Ping，首次延迟5秒

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
                // 静音帧发送失败不应该触发ASR重置，只记录日志
                log.debug("静音帧发送异常（忽略）: {}", e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS));

        // ASR 健康检查任务：检测 ASR 是否静默失效
        tasks.add(scheduler.scheduleAtFixedRate(() -> {
            if (!session.isOpen() || !activeSessions.containsKey(sessionId)) return;
            if (isSessionBusy(sessionId)) return; // 忙碌时不检查
            
            AliyunRealtimeASR currentAsr = asrServices.get(sessionId);
            if (currentAsr == null) return;
            
            long now = System.currentTimeMillis();
            long frameCount = state.audioFrameCount;
            long lastResultTime = state.lastAsrResultTime;
            
            // 检查1：ASR 自身报告不健康（发送失败次数过多）
            boolean asrUnhealthy = !currentAsr.isHealthy();
            
            boolean expectingSpeech = state.awake.get() && (now - state.awakeTime) <= AWAKE_TIMEOUT_MS;
            boolean noResultsForLong = expectingSpeech && frameCount > 500 && (now - lastResultTime) > 60000;
            
            if (asrUnhealthy || noResultsForLong) {
                if (asrUnhealthy) {
                    log.warn("ASR 健康检查失败(发送失败): session={}, sendFailCount={}", 
                            sessionId, currentAsr.getSendFailCount());
                } else {
                    log.warn("ASR 健康检查失败(无响应): session={}, frameCount={}, lastResultAge={}ms", 
                            sessionId, frameCount, now - lastResultTime);
                }
                
                // 重置帧计数，避免重复触发
                state.audioFrameCount = 0;
                state.lastAsrResultTime = now;
                
                // 异步重置 ASR
                if (state.resettingAsr.compareAndSet(false, true)) {
                    CompletableFuture.runAsync(() -> {
                        try {
                            resetAsr(session, sessionId, currentAsr);
                        } finally {
                            state.resettingAsr.set(false);
                        }
                    });
                }
            }
        }, 30, 30, TimeUnit.SECONDS)); // 每30秒检查一次

        state.tasks = tasks;
        return tasks;
    }

    @Override
    public void afterConnectionEstablished(@NotNull WebSocketSession session) throws Exception {
        String id = session.getId();
        log.info("ESP32 Connected: {}", id);

        // 获取设备标识（使用远程地址作为设备唯一标识）
        String deviceKey = getDeviceKey(session);

        // 检查是否有同一设备的旧会话，如果有则清理
        String oldSessionId = deviceToSessionMap.get(deviceKey);
        if (oldSessionId != null && !oldSessionId.equals(id)) {
            log.warn("检测到设备重连，清理旧会话: oldSession={}, newSession={}, device={}", oldSessionId, id, deviceKey);
            cleanupSession(oldSessionId, "设备重连");
        }

        // 记录设备-会话映射
        deviceToSessionMap.put(deviceKey, id);

        registerSession(session, id);

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

            // 处理分片消息：将数据追加到缓冲区
            state.fragmentBuffer.write(data);

            // 如果不是最后一个分片，等待更多数据
            if (!message.isLast()) {
                return;
            }

            // 获取完整消息并清空缓冲区
            byte[] opusData = state.fragmentBuffer.toByteArray();
            state.fragmentBuffer.reset();

            if (opusData.length == 0) return;

            state.lastAsrSendTime = System.currentTimeMillis();
            state.audioFrameCount++;  // 增加音频帧计数

            AliyunRealtimeASR asr = asrServices.get(id);
            if (asr == null || !asr.isRunning()) {
                ensureAsrReady(session, id);
                return;
            }

            if (isSessionBusy(id)) {
                // 忙碌时只发静音帧维持连接，不识别
                asr.sendOpusStream(OPUS_SILENCE_FRAME);
            } else {
                asr.sendOpusStream(opusData);
            }
        } catch (Exception e) {
            // 捕获所有异常，避免因为数据处理错误导致连接断开
            log.debug("处理音频数据时出现异常（连接保持）: {}", e.getMessage());
            // 清空分片缓冲区，避免残留数据影响后续消息
            state.fragmentBuffer.reset();
        }
    }

    @Override
    protected void handleTextMessage(@NotNull WebSocketSession session, @NotNull TextMessage message) {
        String payload = message.getPayload();
        if (payload == null) return;
        String text = payload.trim();

        // 处理音量指令
        Matcher volumeMatcher = VOLUME_PATTERN.matcher(text);
        if (volumeMatcher.matches()) {
            updateVolume(volumeMatcher.group(1));
            return;
        }
        Matcher volumeJsonMatcher = VOLUME_JSON_PATTERN.matcher(text);
        if (volumeJsonMatcher.find()) {
            updateVolume(volumeJsonMatcher.group(1));
            return;
        }

        // 处理色温指令
        Matcher matcher = LT_PATTERN.matcher(text);
        if (matcher.matches()) {
            String brightness = matcher.group(1);
            String temperature = matcher.group(2);
            updateLightStatus(brightness, temperature);
        }
    }

    private void updateVolume(String volStr) {
        int volume = Integer.parseInt(volStr);
        if (volume < 0) volume = 0;
        if (volume > 100) volume = 100;
        latestVolume.set(volume);
    }

    private void updateLightStatus(String brightness, String temperature) {
        try {
            int brightnessPercent = Integer.parseInt(brightness);
            int temperaturePercent = Integer.parseInt(temperature);
            if (brightnessPercent < 0) brightnessPercent = 0;
            if (brightnessPercent > 100) brightnessPercent = 100;
            if (temperaturePercent < 0) temperaturePercent = 0;
            if (temperaturePercent > 100) temperaturePercent = 100;
            latestLt.set("(" + brightnessPercent + "," + temperaturePercent + ")");
        } catch (NumberFormatException ignored) {}
    }

    private String convertToPinyin(String text) {
        StringBuilder pinyin = new StringBuilder();
        for (char c : text.toCharArray()) {
            try {
                String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(c);
                if (pinyinArray != null && pinyinArray.length > 0) {
                    String raw = pinyinArray[0];
                    pinyin.append(raw.replaceAll("[1-5]", ""));
                } else {
                    pinyin.append(c);
                }
            } catch (Exception e) {
                pinyin.append(c);
            }
        }
        return pinyin.toString().toLowerCase();
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

        // 等待TTS开始生成（最多等待3秒）
        int waitCount = 0;
        while (!hasStarted.get() && waitCount < 30) {
            Thread.sleep(100);
            waitCount++;
        }

        // 短文本不需要太多预缓冲，长文本适当缓冲
        int preBufferMs = text.length() <= 5 ? 50 : 100;
        Thread.sleep(preBufferMs);

        while (!isComplete.get() || !frameQueue.isEmpty()) {
            byte[] frame = frameQueue.poll(50, TimeUnit.MILLISECONDS);
            if (frame != null) {
                if (session.isOpen()) {
                    session.sendMessage(new BinaryMessage(frame));
                    SessionState state = sessionStates.get(id);
                    if (state != null) state.lastAsrSendTime = System.currentTimeMillis();
                    // Opus 60ms帧，发送间隔略小于帧时长以保持流畅
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

        // 验证session是否仍然有效
        if (!activeSessions.containsKey(sessionId) || !session.isOpen()) {
            log.debug("会话已关闭，忽略ASR结果: {}", sessionId);
            return;
        }

        SessionState state = sessionStates.get(sessionId);
        if (state == null) return;

        // 更新 ASR 结果时间和重置帧计数（ASR 仍在正常工作）
        state.lastAsrResultTime = System.currentTimeMillis();
        state.audioFrameCount = 0;

        // 检查是否在TTS播放后的静默期内，防止自说自话
        long elapsed = System.currentTimeMillis() - state.ttsEndTime;
        if (elapsed < TTS_SILENCE_PERIOD_MS) {
            log.debug("忽略静默期内的ASR结果 ({}ms): {}", elapsed, text.trim());
            return;
        }

        String question = text.trim();
        log.info("收到语音识别结果: {}", question);

        String pinyin = convertToPinyin(question);
        String cleanPinyin = pinyin.replaceAll("[^a-z]", "");

        // 检查是否是唤醒词
        if (isWakeWord(cleanPinyin)) {
            log.info("触发唤醒词: 小爱同学 (pinyin: {})", cleanPinyin);
            handleWakeUp(session, sessionId);
            return;
        }

        // 检查会话是否已唤醒
        boolean isAwake = state.awake.get();

        if (!isAwake) {
            // 未唤醒状态，忽略非唤醒词的语音
            log.debug("会话未唤醒，忽略ASR结果: {}", question);
            return;
        }

        // 检查唤醒是否超时
        if (System.currentTimeMillis() - state.awakeTime > AWAKE_TIMEOUT_MS) {
            log.info("唤醒已超时，进入休眠状态");
            setSessionAwake(sessionId, false);
            return;
        }

        // 已唤醒状态，处理用户问题，处理完后回到休眠状态
        log.info("处理唤醒后的用户问题: {}", question);
        handleUserQuestion(session, sessionId, question);
    }

    /**
     * 检测是否为唤醒词
     * 支持完全匹配、包含匹配和相似度匹配
     */
    private boolean isWakeWord(String cleanPinyin) {
        if (cleanPinyin == null || cleanPinyin.isEmpty()) return false;

        // 1. 完全包含唤醒词拼音
        if (cleanPinyin.contains(WAKE_WORD_PINYIN)) {
            return true;
        }

        // 2. 相似度匹配（处理语音识别不准确的情况）
        // 检查是否与唤醒词相似（如"小爱童鞋"、"小艾同学"等）
        double similarity = calculatePinyinSimilarity(cleanPinyin, WAKE_WORD_PINYIN);
        if (similarity >= WAKE_WORD_SIMILARITY_THRESHOLD) {
            log.debug("唤醒词相似度匹配: {} vs {}, similarity={}", cleanPinyin, WAKE_WORD_PINYIN, similarity);
            return true;
        }

        // 3. 检查是否包含相似的子串（处理前后有其他字的情况）
        if (cleanPinyin.length() > WAKE_WORD_PINYIN.length()) {
            // 滑动窗口检查
            int windowSize = WAKE_WORD_PINYIN.length();
            for (int i = 0; i <= cleanPinyin.length() - windowSize; i++) {
                String sub = cleanPinyin.substring(i, i + windowSize);
                double subSimilarity = calculatePinyinSimilarity(sub, WAKE_WORD_PINYIN);
                if (subSimilarity >= WAKE_WORD_SIMILARITY_THRESHOLD) {
                    log.debug("唤醒词子串相似度匹配: {} vs {}, similarity={}", sub, WAKE_WORD_PINYIN, subSimilarity);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 计算两个拼音字符串的相似度
     */
    private double calculatePinyinSimilarity(String p1, String p2) {
        if (p1 == null || p2 == null) return 0;
        if (p1.equals(p2)) return 1.0;

        // 使用编辑距离计算相似度
        int maxLen = Math.max(p1.length(), p2.length());
        if (maxLen == 0) return 1.0;

        int editDistance = levenshteinDistance(p1, p2);
        return 1.0 - (double) editDistance / maxLen;
    }

    /**
     * 计算编辑距离（Levenshtein距离）
     */
    private int levenshteinDistance(String s1, String s2) {
        int m = s1.length();
        int n = s2.length();
        int[][] dp = new int[m + 1][n + 1];

        for (int i = 0; i <= m; i++) dp[i][0] = i;
        for (int j = 0; j <= n; j++) dp[0][j] = j;

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(
                                dp[i - 1][j] + 1,      // 删除
                                dp[i][j - 1] + 1),     // 插入
                        dp[i - 1][j - 1] + cost // 替换
                );
            }
        }
        return dp[m][n];
    }

    /**
     * 处理唤醒事件
     */
    private void handleWakeUp(WebSocketSession session, String sessionId) {
        // 如果正在忙碌，忽略唤醒
        if (isSessionBusy(sessionId)) {
            log.debug("会话忙碌中，忽略唤醒请求");
            return;
        }

        // 使用原子操作确保只有一个线程能够进入
        SessionState state = sessionStates.get(sessionId);
        if (state == null) return;
        AtomicBoolean busyState = state.busy;
        if (busyState == null || !busyState.compareAndSet(false, true)) {
            log.debug("唤醒请求被拦截（并发保护）");
            return;
        }

        new Thread(() -> {
            try {
                if (!session.isOpen() || !activeSessions.containsKey(sessionId)) {
                    return;
                }

                log.info("播放唤醒响应: {}", WAKE_RESPONSE);
                playTts(session, sessionId, WAKE_RESPONSE);

                // 设置会话为已唤醒状态
                setSessionAwake(sessionId, true);
                state.awakeTime = System.currentTimeMillis();

                log.info("会话已唤醒，等待用户指令: {}", sessionId);

                // 短暂延迟后解锁，开始监听用户指令
                Thread.sleep(300);

            } catch (Exception e) {
                log.error("唤醒处理失败", e);
            } finally {
                // 确保无论如何都解锁
                setSessionBusy(sessionId, false);
            }
        }).start();
    }

    /**
     * 设置会话唤醒状态
     */
    private void setSessionAwake(String sessionId, boolean awake) {
        SessionState state = sessionStates.get(sessionId);
        if (state == null) return;
        state.awake.set(awake);
        log.info("会话唤醒状态变更: {} -> {}", sessionId, awake ? "已唤醒" : "休眠");
    }

    private void handleUserQuestion(WebSocketSession session, String sessionId, String question) {
        // 双重检查：确保会话有效且未被清理
        if (!activeSessions.containsKey(sessionId) || !session.isOpen()) {
            log.warn("handleUserQuestion: 会话已失效，跳过处理: {}", sessionId);
            return;
        }

        // 如果已经在忙碌状态，防止重复调用
        SessionState state = sessionStates.get(sessionId);
        if (state == null) return;
        AtomicBoolean busyState = state.busy;
        if (busyState == null || !busyState.compareAndSet(false, true)) {
            log.warn("handleUserQuestion: 会话忙碌中或状态异常，跳过: {}", sessionId);
            return;
        }

        new Thread(() -> {
            try {
                // 再次验证会话有效性
                if (!activeSessions.containsKey(sessionId) || !session.isOpen()) {
                    log.warn("处理线程: 会话已失效，跳过: {}", sessionId);
                    setSessionBusy(sessionId, false);
                    return;
                }

                // 先检测用户问题中是否有控制命令
                String controlCommand = detectControlCommand(question);
                if (controlCommand != null) {
                    log.info("检测到控制命令: {}, 原问题: {}", controlCommand, question);
                    // 发送控制命令给设备
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(controlCommand));
                        log.info("已发送控制命令到设备: {}", controlCommand);
                    }

                    // 控制命令已执行，播放简短确认语音，不再请求智能体
                    String confirmText = getControlCommandConfirmText(controlCommand);
                    if (confirmText != null) {
                        playTts(session, sessionId, confirmText);
                    }

                    // 延迟后解锁
                    Thread.sleep(800);
                    setSessionAwake(sessionId, false);
                    setSessionBusy(sessionId, false);
                    log.info("控制命令处理完成，会话已解锁");
                    return;
                }

                String shouldUseVoiceId = currentVoiceId;
                Double shouldUseSpeed = currentSpeedRatio;

                log.info("请求智能体: '{}' (Locking session)", question);
                String[] response = cozeAPI.CozeRequest(question, shouldUseVoiceId, shouldUseSpeed, true);

                if (response != null && response.length >= 2) {
                    String replyText = response[1];
                    log.info("智能体文本回复: {}", replyText);

                    if (!session.isOpen()) { setSessionBusy(sessionId, false); return; }

                    playTts(session, sessionId, replyText);

                    // 延长解锁延迟，等待声音完全播放完毕和回声消散
                    long unlockDelay = 1200;
                    scheduler.schedule(() -> {
                        // 处理完成后设为休眠状态，需要再次唤醒
                        setSessionAwake(sessionId, false);
                        setSessionBusy(sessionId, false);
                        log.info("会话已解锁并进入休眠状态，等待唤醒词");
                    }, unlockDelay, TimeUnit.MILLISECONDS);

                } else {
                    setSessionAwake(sessionId, false);
                    setSessionBusy(sessionId, false);
                }
            } catch (Exception e) {
                log.error("处理失败", e);
                setSessionAwake(sessionId, false);
                setSessionBusy(sessionId, false);
            }
        }).start();
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
            if (!activeSessions.containsKey(sessionId)) {
                return;
            }
            AliyunRealtimeASR currentAsr = asrServices.get(sessionId);
            if (currentAsr == null || currentAsr != asr) {
                return;
            }
            if (isSessionBusy(sessionId)) return;
            handleAsrText(session, sessionId, text);
        });

        asr.setOnActivityCallback(() -> {
            if (!activeSessions.containsKey(sessionId)) {
                return;
            }
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

    /**
     * 检测用户问题中是否包含控制命令
     * @return 如果检测到命令，返回格式化的命令字符串如"(volume_up,15)"；否则返回null
     */
    private String detectControlCommand(String question) {
        if (question == null || question.isEmpty()) return null;

        int defaultStep = 15; // 默认调整幅度

        if (question.contains("关灯")) {
            return "(brightness_down,100)";
        }
        if (question.contains("开灯")) {
            return "(brightness_up,50)";
        }
        if (question.contains("调低亮度")) {
            return "(brightness_down,10)";
        }
        if (question.contains("调高亮度")) {
            return "(brightness_up,10)";
        }
        if (question.contains("调高色温")) {
            return "(tem_up,10)";
        }
        if (question.contains("调低色温")) {
            return "(tem_down,10)";
        }

        // 检测音量调高
        if (CMD_VOLUME_UP.matcher(question).find()) {
            return "(volume_up," + defaultStep + ")";
        }
        // 检测音量调低
        if (CMD_VOLUME_DOWN.matcher(question).find()) {
            return "(volume_down," + defaultStep + ")";
        }
        // 检测亮度调高
        if (CMD_BRIGHTNESS_UP.matcher(question).find()) {
            return "(brightness_up," + defaultStep + ")";
        }
        // 检测亮度调低
        if (CMD_BRIGHTNESS_DOWN.matcher(question).find()) {
            return "(brightness_down," + defaultStep + ")";
        }
        // 检测色温调暖
        if (CMD_TEMP_UP.matcher(question).find()) {
            return "(tem_up," + defaultStep + ")";
        }
        // 检测色温调冷
        if (CMD_TEMP_DOWN.matcher(question).find()) {
            return "(tem_down," + defaultStep + ")";
        }

        return null;
    }

    /**
     * 根据控制命令返回对应的确认语音文本
     */
    private String getControlCommandConfirmText(String command) {
        if (command == null) return null;

        if (command.equals("(brightness_down,100)") || command.equals("(brightness_up,50)")) {
            return "好的";
        }
        if (command.equals("(brightness_down,10)")) {
            return "好的，已将灯光亮度调低10%";
        }
        if (command.equals("(brightness_up,10)")) {
            return "好的，已将灯光亮度调高10%";
        }
        if (command.equals("(tem_up,10)")) {
            return "好的，已将灯光色温调高10%";
        }
        if (command.equals("(tem_down,10)")) {
            return "好的，已将灯光色温调低10%";
        }

        if (command.contains("volume_up")) {
            return "好的，音量已调高";
        } else if (command.contains("volume_down")) {
            return "好的，音量已调低";
        } else if (command.contains("brightness_up")) {
            return "好的，亮度已调高";
        } else if (command.contains("brightness_down")) {
            return "好的，亮度已调低";
        } else if (command.contains("tem_up")) {
            return "好的，色温已调暖";
        } else if (command.contains("tem_down")) {
            return "好的，色温已调冷";
        }

        return "好的";
    }

    private void resetAsr(WebSocketSession session, String id, AliyunRealtimeASR oldAsr) {
        log.info("正在重置 ASR 会话: {}", id);

        // 首先从Map中移除，防止保活任务继续使用
        asrServices.remove(id);

        // 强制停止旧的ASR实例
        if (oldAsr != null) {
            try {
                oldAsr.forceStop();
                log.info("旧 ASR 实例已强制停止");
            } catch (Exception e) {
                log.warn("强制停止旧 ASR 异常: {}", e.getMessage());
            }
        }

        if (!session.isOpen()) {
            log.info("会话已关闭，不再重置 ASR");
            return;
        }

        try {
            // 等待阿里云端释放并发槽位
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return;
        }

        // 检查会话是否仍然打开
        if (!session.isOpen()) {
            log.info("等待期间会话已关闭，取消重置");
            return;
        }

        try {
            AliyunRealtimeASR asr = startAsrForSession(session, id);
            if (asr != null) {
                log.info("ASR 重置成功: {}", id);
            }
        } catch (Exception e) {
            log.error("ASR 重置启动失败: {}", e.getMessage());
        }
    }

    private void ensureAsrReady(WebSocketSession session, String id) {
        if (!session.isOpen() || !activeSessions.containsKey(id)) {
            return;
        }
        SessionState state = sessionStates.get(id);
        if (state == null) return;
        if (!state.resettingAsr.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
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
        
        // 根据关闭码记录更详细的信息
        switch (status.getCode()) {
            case 1000:
                log.info("正常关闭: session={}", id);
                break;
            case 1001:
                log.info("端点离开: session={}", id);
                break;
            case 1002:
                log.warn("协议错误断开: session={}, 可能是帧格式问题或心跳冲突", id);
                break;
            case 1003:
                log.warn("不支持的数据类型: session={}", id);
                break;
            case 1006:
                log.warn("异常关闭（无关闭帧）: session={}, 可能是网络中断", id);
                break;
            case 1009:
                log.warn("消息过大: session={}", id);
                break;
            case 1011:
                log.warn("服务器错误: session={}", id);
                break;
            default:
                log.info("其他关闭码: session={}, code={}", id, status.getCode());
        }
        
        cleanupSession(id, "连接关闭: code=" + status.getCode());
    }

    /**
     * 获取设备唯一标识（使用远程地址）
     */
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

    /**
     * 统一的会话清理方法，确保资源被完全释放
     */
    private synchronized void cleanupSession(String sessionId, String reason) {
        log.info("开始清理会话: id={}, reason={}", sessionId, reason);

        if (!activeSessions.containsKey(sessionId) && !asrServices.containsKey(sessionId)) {
            return;
        }

        // 1. 取消所有定时任务并清理分片缓冲区
        SessionState state = sessionStates.remove(sessionId);
        if (state != null) {
            if (state.tasks != null) {
                state.tasks.forEach(t -> t.cancel(false));
            }
            // 清理分片缓冲区
            try {
                state.fragmentBuffer.reset();
            } catch (Exception ignored) {}
        }

        // 2. 移除会话相关状态
        WebSocketSession session = activeSessions.remove(sessionId);

        // 3. 清理设备映射
        if (session != null) {
            deviceToSessionMap.remove(getDeviceKey(session), sessionId);
        }

        // 4. 异步强制停止ASR资源
        AliyunRealtimeASR asr = asrServices.remove(sessionId);
        if (asr != null) {
            CompletableFuture.runAsync(() -> {
                try { asr.forceStop(); } catch (Exception ignored) {}
            });
        }
    }

    /**
     * 处理 Pong 响应，更新心跳时间
     */
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

    @Override
    public boolean supportsPartialMessages() {
        return true;  // 启用分片消息支持，处理 opCode 0 延续帧
    }

    public String getLatestLt() {
        return latestLt.get();
    }

    public int getLatestVolume() {
        return latestVolume.get();
    }

    public void broadcastControlMessage(String message) {
        log.info("广播控制指令: {}", message);
        activeSessions.values().forEach(session -> {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(message));
                } catch (Exception e) {
                    log.error("广播失败", e);
                }
            }
        });
    }
}
