package com.example.edog.service;

import com.alibaba.nls.client.protocol.InputFormatEnum;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriber;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberListener;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberResponse;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Aliyun realtime ASR helper (compatible with NLS SDK 2.2.x).
 */
public class AliyunRealtimeASR {
    private static final Logger log = LoggerFactory.getLogger(AliyunRealtimeASR.class);

    // Shared NlsClient to avoid repeated handshakes; guarded by clientLock.
    private static NlsClient nlsClient;
    private static String currentClientToken = "";
    private static final Object clientLock = new Object();

    private SpeechTranscriber transcriber;
    private OpusDecoder opusDecoder;
    private Consumer<String> textCallback;

    // 这个标志位现在是核心判断依据，只要它为 true，就说明 start() 中的 CountDownLatch 已经通过，ASR 绝对就绪
    private volatile boolean isRunning = false;

    private final short[] pcmBuffer = new short[5760];
    private final String appKey;

    // 解码器错误计数，用于触发重置
    private volatile int decodeErrorCount = 0;
    private static final int MAX_DECODE_ERRORS = 3; // 连续3次解码失败后重置解码器

    public AliyunRealtimeASR(String appKey) {
        this.appKey = appKey;
        initOpusDecoder();
    }

    /**
     * 初始化或重置Opus解码器
     */
    private synchronized void initOpusDecoder() {
        try {
            this.opusDecoder = new OpusDecoder(16000, 1);
            this.decodeErrorCount = 0;
            log.debug("Opus decoder 初始化成功");
        } catch (OpusException e) {
            log.error("Opus decoder 初始化失败", e);
        }
    }

    /**
     * 重置Opus解码器，用于从损坏的流状态恢复
     */
    private synchronized void resetOpusDecoder() {
        log.info("重置Opus解码器以恢复解码状态");
        initOpusDecoder();
    }

    public void setOnResultCallback(Consumer<String> callback) {
        this.textCallback = callback;
    }

    public static void shutdownSharedClient() {
        synchronized (clientLock) {
            if (nlsClient != null) {
                try {
                    nlsClient.shutdown();
                } catch (Exception e) {
                    log.warn("关闭旧 NlsClient 失败", e);
                } finally {
                    nlsClient = null;
                    currentClientToken = "";
                }
            }
        }
    }

    /**
     * Start ASR with the latest valid token.
     * 此方法会阻塞直到 ASR 握手完成，或者超时
     */
    public void start(String token) {
        if (appKey == null || appKey.isEmpty()) {
            log.error("ASR 启动失败：AppKey 为空");
            throw new IllegalArgumentException("AppKey is null or empty");
        }
        if (token == null || token.isEmpty()) {
            log.error("ASR 启动失败：Token 为空");
            throw new IllegalArgumentException("Token is null or empty");
        }

        try {
            // 核心修复 1：定义同步锁，等待回调确认
            CountDownLatch readyLatch = new CountDownLatch(1);
            AtomicReference<String> startError = new AtomicReference<>();

            synchronized (clientLock) {
                if (nlsClient == null || !token.equals(currentClientToken)) {
                    log.info("检测到 Token 变更或 Client 未初始化，正在重置 NlsClient...");
                    shutdownSharedClient(); // 使用封装好的方法安全关闭
                    nlsClient = new NlsClient(token);
                    currentClientToken = token;
                    log.info("NlsClient 初始化/刷新成功");
                }
            }

            transcriber = new SpeechTranscriber(nlsClient, getListener(readyLatch, startError));
            transcriber.setAppKey(appKey);
            transcriber.setFormat(InputFormatEnum.PCM);
            transcriber.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
            transcriber.setEnablePunctuation(true);
            transcriber.addCustomedParam("enable_inverse_text_normalization", true);
            transcriber.setEnableIntermediateResult(false);

            transcriber.start();

            // 核心修复 2：阻塞等待，确保连接真正建立
            boolean ready = readyLatch.await(5, TimeUnit.SECONDS);
            if (!ready) {
                stop();
                throw new RuntimeException("ASR 启动超时 (5s)");
            }
            if (startError.get() != null && !startError.get().isEmpty()) {
                stop();
                throw new RuntimeException("ASR 启动回调报错: " + startError.get());
            }

            // 只有到了这里，isRunning 才置为 true，此时 sendOpusStream 才可以工作
            isRunning = true;
            log.info("ASR 会话启动成功 (Ready to receive audio)");

        } catch (Exception e) {
            log.error("ASR 启动异常", e);
            isRunning = false;
            stop();
            // 如果是频繁请求错误，主动休眠退避，防止死循环
            if (shouldBackoff(e)) {
                sleepBackoff();
            }
            throw new RuntimeException("ASR 启动异常", e);
        }
    }

    public void sendOpusStream(byte[] opusBytes) {
        // 核心修复 3：移除 isClientOpen 反射检查，完全信任 isRunning
        if (!isRunning || transcriber == null) return;
        if (opusBytes == null || opusBytes.length == 0) return;

        // 可选：如果 nlsClient 丢了，也没必要发
        if (nlsClient == null) {
            log.warn("NlsClient is null, skipping frame");
            return;
        }

        // 检查解码器是否可用
        if (opusDecoder == null) {
            log.warn("OpusDecoder is null, attempting to reinitialize");
            initOpusDecoder();
            if (opusDecoder == null) return;
        }

        try {
            int samples = opusDecoder.decode(opusBytes, 0, opusBytes.length, pcmBuffer, 0, pcmBuffer.length, false);
            if (samples > 0) {
                // 解码成功，重置错误计数
                decodeErrorCount = 0;
                
                byte[] pcmData = new byte[samples * 2];
                for (int i = 0; i < samples; i++) {
                    short s = pcmBuffer[i];
                    pcmData[i * 2] = (byte) (s & 0xff);
                    pcmData[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
                }
                transcriber.send(pcmData);
            }
        } catch (Exception e) {
            // 解码异常处理：不要让异常导致连接断开
            decodeErrorCount++;
            
            // 只在首次失败时打印警告，避免日志刷屏
            if (decodeErrorCount == 1) {
                log.warn("ASR Opus解码异常 (将尝试恢复): {}", e.getMessage());
            }
            
            // 连续多次解码失败，重置解码器
            if (decodeErrorCount >= MAX_DECODE_ERRORS) {
                log.info("连续{}次解码失败，重置解码器", decodeErrorCount);
                resetOpusDecoder();
            }
            
            // 不抛出异常，让连接保持
        }
    }

    public void stop() {
        isRunning = false;
        SpeechTranscriber localTranscriber = this.transcriber;
        this.transcriber = null;

        if (localTranscriber != null) {
            try {
                // 先发送stop指令，告知服务端停止识别
                localTranscriber.stop();
                // 等待服务端确认，确保并发槽位被释放
                Thread.sleep(100);
            } catch (Exception e) {
                log.debug("transcriber.stop 异常: {}", e.getMessage());
            }

            try {
                // 关闭底层连接，释放资源
                localTranscriber.close();
                // 等待连接完全关闭
                Thread.sleep(200);
            } catch (Exception e) {
                log.debug("transcriber.close 异常: {}", e.getMessage());
            }
        }
        log.info("ASR 会话已停止并释放资源");
    }

    /**
     * 强制停止并释放所有资源，用于断连时确保资源被释放
     * 优化：减少等待时间，快速释放
     */
    public void forceStop() {
        isRunning = false;
        SpeechTranscriber localTranscriber = this.transcriber;
        this.transcriber = null;

        if (localTranscriber != null) {
            // 1. 先尝试正常stop，让服务端知道我们要停止
            try {
                localTranscriber.stop();
            } catch (Exception e) {
                log.debug("transcriber.stop 异常: {}", e.getMessage());
            }

            // 2. 立即关闭连接，不等待
            try {
                localTranscriber.close();
            } catch (Exception e) {
                log.debug("transcriber.close 异常: {}", e.getMessage());
            }
        }

        log.info("ASR 会话已强制停止");
    }

    /**
     * 检查ASR是否正在运行
     */
    public boolean isRunning() {
        return isRunning && transcriber != null;
    }

    private SpeechTranscriberListener getListener(CountDownLatch readyLatch, AtomicReference<String> startError) {
        return new SpeechTranscriberListener() {
            @Override
            public void onTranscriberStart(SpeechTranscriberResponse response) {
                log.info("任务开始 {}", response.getTaskId());
                // 收到这个回调，说明连接成功，解锁 start() 方法
                readyLatch.countDown();
            }

            @Override
            public void onTranscriptionComplete(SpeechTranscriberResponse response) {
                log.info("任务结束: {}", response.getStatus());
            }

            @Override
            public void onSentenceEnd(SpeechTranscriberResponse response) {
                String text = response.getTransSentenceText();
                log.info("识别结果: {}", text);
                if (textCallback != null && text != null && !text.isEmpty()) {
                    textCallback.accept(text);
                }
            }

            @Override
            public void onSentenceBegin(SpeechTranscriberResponse response) {}

            @Override
            public void onTranscriptionResultChange(SpeechTranscriberResponse response) {}

            @Override
            public void onFail(SpeechTranscriberResponse response) {
                log.error("ASR Error: {}", response.getStatusText());
                startError.set(response.getStatusText());
                // 即使失败也要解锁，让 start() 方法能抛出异常并结束
                readyLatch.countDown();
            }
        };
    }

    // 移除了 isClientOpen 方法

    private boolean shouldBackoff(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String upper = msg.toUpperCase();
        return upper.contains("TOO_MANY_REQUESTS") || upper.contains("GATEWAY:TOO_MANY_REQUESTS");
    }

    private void sleepBackoff() {
        try {
            log.warn("触发熔断机制，暂停 2 秒...");
            Thread.sleep(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}