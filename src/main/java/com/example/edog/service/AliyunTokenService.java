package com.example.edog.service;

import com.alibaba.nls.client.AccessToken;
import com.example.edog.utils.AliyunCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Lightweight manager for Aliyun ASR tokens.
 * Refreshes tokens on demand and ensures we never hand out an invalid token.
 */
@Service
public class AliyunTokenService {

    private static final Logger log = LoggerFactory.getLogger(AliyunTokenService.class);

    private final AliyunCredentials credentials;

    private String token;
    // Expire time in seconds since epoch.
    private long expireTime = 0;

    @Autowired
    public AliyunTokenService(AliyunCredentials credentials) {
        this.credentials = credentials;
    }

    /**
     * Get a valid token. Refreshes if missing or expiring within 10 minutes.
     */
    public synchronized String getToken() {
        long now = System.currentTimeMillis() / 1000;

        if (token == null || (expireTime - now) < 600) {
            log.info("Token 即将过期或不存在，正在刷新...");
            refreshToken();
        }
        return token;
    }

    private void refreshToken() {
        try {
            AccessToken accessToken = new AccessToken(
                    credentials.getAccessKeyId(),
                    credentials.getAccessKeySecret()
            );
            accessToken.apply();

            String newToken = accessToken.getToken();
            long newExpireTime = accessToken.getExpireTime();

            // Guard against silent failures (e.g., invalid AK) that return empty token or 0 expiry.
            if (newToken == null || newToken.isEmpty() || newExpireTime <= 0) {
                throw new IllegalStateException("Aliyun 返回空 Token 或无效过期时间，疑似 AccessKey 配置错误");
            }

            this.token = newToken;
            this.expireTime = newExpireTime;

            log.info("Token 刷新成功, 有效期至: {} (时间戳: {})",
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(expireTime * 1000)),
                    expireTime);

        } catch (IOException e) {
            log.error("获取阿里云 Token 失败", e);
            throw new RuntimeException("无法获取语音识别 Token", e);
        }
    }
}
