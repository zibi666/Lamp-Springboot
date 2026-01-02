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
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final Map<String, AtomicBoolean> asrResettingState = new ConcurrentHashMap<>();

    private final Map<String, Long> lastAsrSendTime = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    // 心跳超时检测
    private final Map<String, Long> lastPongTime = new ConcurrentHashMap<>();
    private static final long HEARTBEAT_TIMEOUT_MS = 8000; // 8秒无响应视为断连

    // 设备标识映射，用于检测同一设备重连（通过远程地址或设备ID）
    private final Map<String, String> deviceToSessionMap = new ConcurrentHashMap<>();

    // TTS播放结束后的静默期，防止麦克风拾取自己的声音导致自说自话
    private final Map<String, Long> ttsEndTime = new ConcurrentHashMap<>();
    private static final long TTS_SILENCE_PERIOD_MS = 1500; // TTS结束后1.5秒内忽略ASR结果

    // 唤醒词机制相关
    private final Map<String, AtomicBoolean> sessionAwakeState = new ConcurrentHashMap<>(); // 会话是否已被唤醒
    private final Map<String, Long> awakeTime = new ConcurrentHashMap<>(); // 唤醒时间，用于超时自动休眠
    private static final long AWAKE_TIMEOUT_MS = 30000; // 唤醒后30秒无响应则自动休眠
    private static final String WAKE_WORD_PINYIN = "xiaoaitongxue"; // 唤醒词拼音
    private static final String WAKE_RESPONSE = "我在呢"; // 唤醒词响应
    private static final double WAKE_WORD_SIMILARITY_THRESHOLD = 0.75; // 唤醒词相似度阈值

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

        activeSessions.put(id, session);
        sessionBusyState.put(id, new AtomicBoolean(false));
        asrResettingState.put(id, new AtomicBoolean(false));
        sessionAwakeState.put(id, new AtomicBoolean(false)); // 初始为休眠状态，等待唤醒词
        lastAsrSendTime.put(id, System.currentTimeMillis());
        lastPongTime.put(id, System.currentTimeMillis());

        AliyunRealtimeASR asr = new AliyunRealtimeASR(credentials.getAppKey());
        asr.setOnResultCallback(text -> {
            // 验证session是否仍然有效（防止设备重连后旧回调触发）
            if (!activeSessions.containsKey(id)) {
                log.debug("忽略已清理会话的ASR回调: {}", id);
                return;
            }
            // 验证当前ASR是否是此会话的活跃ASR
            AliyunRealtimeASR currentAsr = asrServices.get(id);
            if (currentAsr == null || currentAsr != asr) {
                log.debug("忽略已替换ASR实例的回调: {}", id);
                return;
            }
            if (isSessionBusy(id)) return;
            handleAsrText(session, id, text);
        });

        try {
            String token = tokenService.getToken();
            asr.start(token);
            asrServices.put(id, asr);
            log.info("ASR 初始启动成功: {}", id);
        } catch (Exception e) {
            log.error("ASR 初始启动失败: {}", e.getMessage());
            // 确保启动失败时释放资源
            try {
                asr.forceStop();
            } catch (Exception ignored) {}
            // 不放入asrServices，后续重试逻辑可以处理
        }

        // 启动定时任务
        Timer timer = new Timer(true);

        // 1. WebSocket 心跳 - 每2秒发送一次，更快检测断连
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(new PingMessage());
                    } else {
                        // session已关闭，触发清理
                        log.warn("心跳检测到session已关闭: {}", id);
                        cleanupSession(id, "心跳检测session关闭");
                    }
                } catch (Exception e) {
                    log.warn("发送心跳失败，可能已断连: {}", e.getMessage());
                    cleanupSession(id, "心跳发送失败");
                }
            }
        }, 2000, 2000);

        // 2. 心跳超时检测任务
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                String currentId = id;
                try {
                    if (!activeSessions.containsKey(currentId)) {
                        return;
                    }

                    long lastPong = lastPongTime.getOrDefault(currentId, 0L);
                    long now = System.currentTimeMillis();

                    if (now - lastPong > HEARTBEAT_TIMEOUT_MS) {
                        log.warn("心跳超时检测: session={}, lastPong={}ms前", currentId, now - lastPong);
                        // 触发会话清理
                        cleanupSession(currentId, "心跳超时");
                        // 尝试关闭WebSocket连接
                        try {
                            if (session.isOpen()) {
                                session.close(CloseStatus.GOING_AWAY);
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    log.error("心跳超时检测异常: {}", e.getMessage());
                }
            }
        }, 10000, 5000);

        // 3. ASR 保活任务 & 重试逻辑
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                String currentId = id;
                try {
                    // 如果会话已关闭或不在活跃列表中，不再执行任何操作
                    if (!session.isOpen() || !activeSessions.containsKey(currentId)) {
                        return;
                    }

                    AliyunRealtimeASR currentAsr = asrServices.get(currentId);

                    // 如果 ASR 不存在或已停止，不处理（由其他逻辑负责重建）
                    if (currentAsr == null || !currentAsr.isRunning()) {
                        return;
                    }

                    long lastTime = lastAsrSendTime.getOrDefault(currentId, 0L);
                    long now = System.currentTimeMillis();

                    // 发送静音帧保活
                    if (now - lastTime > 800) {
                        currentAsr.sendOpusStream(OPUS_SILENCE_FRAME);
                        lastAsrSendTime.put(currentId, now);
                    }
                } catch (Exception e) {
                    log.error("保活检查异常: {}", e.getMessage());
                    // 只有会话仍然活跃时才尝试重置
                    if (session.isOpen() && activeSessions.containsKey(currentId)) {
                        AliyunRealtimeASR oldAsr = asrServices.get(currentId);
                        if (oldAsr != null) {
                            resetAsr(session, currentId, oldAsr);
                        }
                    }
                }
            }
        }, 1000, 500);

        sessionTimers.put(id, timer);
    }

    @Override
    protected void handleBinaryMessage(@NotNull WebSocketSession session, @NotNull BinaryMessage message) {
        String id = session.getId();
        AliyunRealtimeASR asr = asrServices.get(id);

        if (asr == null || !asr.isRunning()) {
            ensureAsrReady(session, id);
            return;
        }

        if (asr != null) {
            try {
                lastAsrSendTime.put(id, System.currentTimeMillis());

                if (isSessionBusy(id)) {
                    // 忙碌时只发静音帧维持连接，不识别
                    asr.sendOpusStream(OPUS_SILENCE_FRAME);
                } else {
                    ByteBuffer payload = message.getPayload();
                    byte[] opusData = new byte[payload.remaining()];
                    payload.get(opusData);
                    asr.sendOpusStream(opusData);
                }
            } catch (Exception e) {
                // 如果这里报错，说明 ASR 可能断了，触发重置
                log.error("ASR 发送流异常, 触发重置", e);
                resetAsr(session, id, asr);
            }
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
            int temperatureK = Integer.parseInt(temperature);
            int temperaturePercent = (int) Math.round(temperatureK * 100.0 / MAX_TEMPERATURE_K);
            if (temperaturePercent < 0) temperaturePercent = 0;
            if (temperaturePercent > 100) temperaturePercent = 100;
            latestLt.set("(" + brightness + "," + temperaturePercent + ")");
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
                    lastAsrSendTime.put(id, System.currentTimeMillis());
                    // Opus 60ms帧，发送间隔略小于帧时长以保持流畅
                    Thread.sleep(45);
                } else {
                    break;
                }
            }
        }
    }

    private void handleAsrText(WebSocketSession session, String sessionId, String text) {
        if (text == null || text.trim().isEmpty()) return;

        // 验证session是否仍然有效
        if (!activeSessions.containsKey(sessionId) || !session.isOpen()) {
            log.debug("会话已关闭，忽略ASR结果: {}", sessionId);
            return;
        }

        // 检查是否在TTS播放后的静默期内，防止自说自话
        Long lastTtsEnd = ttsEndTime.get(sessionId);
        if (lastTtsEnd != null) {
            long elapsed = System.currentTimeMillis() - lastTtsEnd;
            if (elapsed < TTS_SILENCE_PERIOD_MS) {
                log.debug("忽略静默期内的ASR结果 ({}ms): {}", elapsed, text.trim());
                return;
            }
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
        AtomicBoolean awakeState = sessionAwakeState.get(sessionId);
        boolean isAwake = awakeState != null && awakeState.get();

        if (!isAwake) {
            // 未唤醒状态，忽略非唤醒词的语音
            log.debug("会话未唤醒，忽略ASR结果: {}", question);
            return;
        }

        // 检查唤醒是否超时
        Long awakeAt = awakeTime.get(sessionId);
        if (awakeAt != null && System.currentTimeMillis() - awakeAt > AWAKE_TIMEOUT_MS) {
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
        AtomicBoolean busyState = sessionBusyState.get(sessionId);
        if (busyState == null || !busyState.compareAndSet(false, true)) {
            log.debug("唤醒请求被拦截（并发保护）");
            return;
        }

        new Thread(() -> {
            try {
                if (!session.isOpen() || !activeSessions.containsKey(sessionId)) {
                    return;
                }

                // 播放唤醒响应
                log.info("播放唤醒响应: {}", WAKE_RESPONSE);
                session.sendMessage(new TextMessage("{\"type\":\"tts\",\"state\":\"start\"}"));
                sendTtsStream(session, WAKE_RESPONSE);
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage("{\"type\":\"tts\",\"state\":\"end\"}"));
                }

                // 记录TTS结束时间
                ttsEndTime.put(sessionId, System.currentTimeMillis());

                // 设置会话为已唤醒状态
                setSessionAwake(sessionId, true);
                awakeTime.put(sessionId, System.currentTimeMillis());

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
        AtomicBoolean state = sessionAwakeState.get(sessionId);
        if (state != null) {
            state.set(awake);
            log.info("会话唤醒状态变更: {} -> {}", sessionId, awake ? "已唤醒" : "休眠");
        }
    }

    private void handleUserQuestion(WebSocketSession session, String sessionId, String question) {
        // 双重检查：确保会话有效且未被清理
        if (!activeSessions.containsKey(sessionId) || !session.isOpen()) {
            log.warn("handleUserQuestion: 会话已失效，跳过处理: {}", sessionId);
            return;
        }

        // 如果已经在忙碌状态，防止重复调用
        AtomicBoolean busyState = sessionBusyState.get(sessionId);
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
                    if (confirmText != null && session.isOpen()) {
                        session.sendMessage(new TextMessage("{\"type\":\"tts\",\"state\":\"start\"}"));
                        sendTtsStream(session, confirmText);
                        if (session.isOpen()) {
                            session.sendMessage(new TextMessage("{\"type\":\"tts\",\"state\":\"end\"}"));
                        }
                        ttsEndTime.put(sessionId, System.currentTimeMillis());
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

                    session.sendMessage(new TextMessage("{\"type\":\"tts\",\"state\":\"start\"}"));
                    // 不再发送固定音量，让设备保持自己的音量设置
                    sendTtsStream(session, replyText);

                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage("{\"type\":\"tts\",\"state\":\"end\"}"));
                    }

                    // 记录TTS结束时间，用于静默期检测
                    ttsEndTime.put(sessionId, System.currentTimeMillis());

                    // 延长解锁延迟，等待声音完全播放完毕和回声消散
                    long unlockDelay = 1200;
                    new Timer().schedule(new TimerTask() {
                        @Override
                        public void run() {
                            // 处理完成后设为休眠状态，需要再次唤醒
                            setSessionAwake(sessionId, false);
                            setSessionBusy(sessionId, false);
                            log.info("会话已解锁并进入休眠状态，等待唤醒词");
                        }
                    }, unlockDelay);

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
        AtomicBoolean state = sessionBusyState.get(sessionId);
        return state != null && state.get();
    }

    private void setSessionBusy(String sessionId, boolean busy) {
        AtomicBoolean state = sessionBusyState.get(sessionId);
        if (state != null) state.set(busy);
    }

    /**
     * 检测用户问题中是否包含控制命令
     * @return 如果检测到命令，返回格式化的命令字符串如"(volume_up,15)"；否则返回null
     */
    private String detectControlCommand(String question) {
        if (question == null || question.isEmpty()) return null;

        int defaultStep = 15; // 默认调整幅度

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

        AliyunRealtimeASR asr = new AliyunRealtimeASR(credentials.getAppKey());
        asr.setOnResultCallback(text -> {
            // 验证session是否仍然有效（防止设备重连后旧回调触发）
            if (!activeSessions.containsKey(id)) {
                log.debug("忽略已清理会话的ASR回调: {}", id);
                return;
            }
            // 验证当前ASR是否是此会话的活跃ASR
            AliyunRealtimeASR currentAsr = asrServices.get(id);
            if (currentAsr == null || currentAsr != asr) {
                log.debug("忽略已替换ASR实例的回调: {}", id);
                return;
            }
            if (isSessionBusy(id)) return;
            handleAsrText(session, id, text);
        });

        try {
            String token = tokenService.getToken();
            asr.start(token);

            // 再次检查会话状态，如果已关闭则停止新创建的ASR
            if (!session.isOpen()) {
                asr.forceStop();
                log.info("ASR 启动后发现会话已关闭，已释放");
                return;
            }

            asrServices.put(id, asr);
            log.info("ASR 重置成功: {}", id);
        } catch (Exception e) {
            log.error("ASR 重置启动失败: {}", e.getMessage());
            // 确保失败时也释放资源
            try {
                asr.forceStop();
            } catch (Exception ignored) {}
        }
    }

    private void ensureAsrReady(WebSocketSession session, String id) {
        if (!session.isOpen() || !activeSessions.containsKey(id)) {
            return;
        }
        AtomicBoolean resetting = asrResettingState.computeIfAbsent(id, key -> new AtomicBoolean(false));
        if (!resetting.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                AliyunRealtimeASR oldAsr = asrServices.get(id);
                resetAsr(session, id, oldAsr);
            } finally {
                resetting.set(false);
            }
        });
    }

    @Override
    public void afterConnectionClosed(@NotNull WebSocketSession session, @NotNull CloseStatus status) {
        String id = session.getId();
        log.info("ESP32 Disconnected: id={}, code={}", id, status.getCode());
        cleanupSession(id, "正常断连");
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

        // 检查是否已经清理过
        if (!activeSessions.containsKey(sessionId) && !asrServices.containsKey(sessionId)) {
            log.debug("会话已被清理过，跳过: {}", sessionId);
            return;
        }

        // 1. 先取消定时器，防止在清理过程中触发保活任务
        Timer timer = sessionTimers.remove(sessionId);
        if (timer != null) {
            try {
                timer.cancel();
                timer.purge();
            } catch (Exception e) {
                log.debug("取消定时器异常: {}", e.getMessage());
            }
        }

        // 2. 移除会话相关状态
        WebSocketSession session = activeSessions.remove(sessionId);
        sessionBusyState.remove(sessionId);
        sessionAwakeState.remove(sessionId); // 清理唤醒状态
        awakeTime.remove(sessionId); // 清理唤醒时间
        lastAsrSendTime.remove(sessionId);
        lastPongTime.remove(sessionId);
        ttsEndTime.remove(sessionId);
        asrResettingState.remove(sessionId);

        // 3. 清理设备映射（如果此会话是当前设备的活跃会话）
        if (session != null) {
            String deviceKey = getDeviceKey(session);
            deviceToSessionMap.remove(deviceKey, sessionId);
        }

        // 4. 异步强制停止ASR资源，不阻塞主流程
        AliyunRealtimeASR asr = asrServices.remove(sessionId);
        if (asr != null) {
            final String sid = sessionId;
            CompletableFuture.runAsync(() -> {
                try {
                    asr.forceStop();
                    log.info("已释放 ASR 资源: {}", sid);
                } catch (Exception e) {
                    log.error("释放 ASR 资源失败: {}", e.getMessage());
                }
            });
        }

        log.info("会话清理完成: id={}, reason={}", sessionId, reason);
    }

    /**
     * 处理 Pong 响应，更新心跳时间
     */
    @Override
    protected void handlePongMessage(@NotNull WebSocketSession session, @NotNull PongMessage message) {
        String id = session.getId();
        lastPongTime.put(id, System.currentTimeMillis());
    }

    @Override
    public void handleTransportError(@NotNull WebSocketSession session, @NotNull Throwable exception) {
        String id = session.getId();
        log.error("WebSocket 传输错误: id={}, error={}", id, exception.getMessage());

        // 传输错误时主动清理会话
        cleanupSession(id, "传输错误: " + exception.getMessage());
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
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
