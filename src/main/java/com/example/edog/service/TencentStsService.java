package com.example.edog.service;

import com.example.edog.configurer.CosProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 通过 STS AssumeRole 申请临时密钥。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TencentStsService {

    private static final String STS_ACTION = "AssumeRole";
    private static final String STS_VERSION = "2018-08-13";
    private static final String STS_SERVICE = "sts";
    private static final String STS_ALGORITHM = "TC3-HMAC-SHA256";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String SIGNED_HEADERS = "content-type;host;x-tc-action";

    private final CosProperties cosProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private volatile TemporaryCredential cachedCredential;

    /**
     * 获取可用临时凭证（内置简单缓存）。
     */
    public TemporaryCredential getTemporaryCredential() {
        TemporaryCredential current = cachedCredential;
        long nowEpochSeconds = Instant.now().getEpochSecond();
        if (current != null && current.getExpiredTime() - nowEpochSeconds > 60) {
            return current;
        }
        synchronized (this) {
            current = cachedCredential;
            nowEpochSeconds = Instant.now().getEpochSecond();
            if (current != null && current.getExpiredTime() - nowEpochSeconds > 60) {
                return current;
            }
            TemporaryCredential refreshed = requestTemporaryCredential();
            cachedCredential = refreshed;
            return refreshed;
        }
    }

    private TemporaryCredential requestTemporaryCredential() {
        String secretId = requireText(cosProperties.getSecretId(), "COS配置缺失: cos.secret-id");
        String secretKey = requireText(cosProperties.getSecretKey(), "COS配置缺失: cos.secret-key");
        String roleArn = requireText(cosProperties.getRoleArn(), "COS配置缺失: cos.role-arn");
        String region = requireText(cosProperties.getRegion(), "COS配置缺失: cos.region");

        int durationSeconds = normalizeStsDuration(cosProperties.getStsDurationSeconds());
        String roleSessionName = StringUtils.hasText(cosProperties.getRoleSessionName())
                ? cosProperties.getRoleSessionName().trim()
                : "lamp-avatar-upload";

        try {
            URI endpointUri = normalizeEndpoint(cosProperties.getStsEndpoint());
            String host = endpointUri.getHost();
            if (!StringUtils.hasText(host)) {
                throw new IllegalStateException("STS接口地址无效: " + cosProperties.getStsEndpoint());
            }

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("RoleArn", roleArn);
            requestBody.put("RoleSessionName", roleSessionName);
            requestBody.put("DurationSeconds", durationSeconds);
            if (StringUtils.hasText(cosProperties.getExternalId())) {
                requestBody.put("ExternalId", cosProperties.getExternalId().trim());
            }
            String payload = objectMapper.writeValueAsString(requestBody);

            long timestamp = Instant.now().getEpochSecond();
            String date = LocalDate.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC).toString();

            String canonicalHeaders = "content-type:" + CONTENT_TYPE + "\n"
                    + "host:" + host + "\n"
                    + "x-tc-action:" + STS_ACTION.toLowerCase(Locale.ROOT) + "\n";
            String canonicalRequest = "POST\n/\n\n"
                    + canonicalHeaders + "\n"
                    + SIGNED_HEADERS + "\n"
                    + sha256Hex(payload);
            String credentialScope = date + "/" + STS_SERVICE + "/tc3_request";
            String stringToSign = STS_ALGORITHM + "\n"
                    + timestamp + "\n"
                    + credentialScope + "\n"
                    + sha256Hex(canonicalRequest);

            String authorization = buildAuthorization(secretId, secretKey, date, credentialScope, stringToSign);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpointUri)
                    .header("Authorization", authorization)
                    .header("Content-Type", CONTENT_TYPE)
                    .header("Host", host)
                    .header("X-TC-Action", STS_ACTION)
                    .header("X-TC-Version", STS_VERSION)
                    .header("X-TC-Timestamp", String.valueOf(timestamp))
                    .header("X-TC-Region", region)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (response.statusCode() != 200) {
                log.error("STS接口HTTP异常 status={}, body={}", response.statusCode(), response.body());
                throw new IllegalStateException("签名生成失败: STS接口调用异常");
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode responseNode = root.path("Response");
            if (responseNode.has("Error")) {
                String errorCode = responseNode.path("Error").path("Code").asText("Unknown");
                String errorMessage = responseNode.path("Error").path("Message").asText("Unknown");
                log.error("STS接口业务异常 code={}, message={}", errorCode, errorMessage);
                throw new IllegalStateException("签名生成失败: " + errorCode + " - " + errorMessage);
            }

            JsonNode credentialsNode = responseNode.path("Credentials");
            String tmpSecretId = credentialsNode.path("TmpSecretId").asText(null);
            String tmpSecretKey = credentialsNode.path("TmpSecretKey").asText(null);
            String token = credentialsNode.path("Token").asText(null);
            long expiredTime = responseNode.path("ExpiredTime").asLong(0L);

            if (!StringUtils.hasText(tmpSecretId)
                    || !StringUtils.hasText(tmpSecretKey)
                    || !StringUtils.hasText(token)
                    || expiredTime <= 0L) {
                log.error("STS返回字段不完整 body={}", response.body());
                throw new IllegalStateException("签名生成失败: STS返回凭证不完整");
            }
            return new TemporaryCredential(tmpSecretId, tmpSecretKey, token, expiredTime);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用STS申请临时密钥失败", e);
            throw new IllegalStateException("签名生成失败: STS调用异常");
        }
    }

    private String buildAuthorization(String secretId, String secretKey, String date,
                                      String credentialScope, String stringToSign) {
        try {
            byte[] secretDate = hmacSha256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
            byte[] secretService = hmacSha256(secretDate, STS_SERVICE);
            byte[] secretSigning = hmacSha256(secretService, "tc3_request");
            String signature = toHex(hmacSha256(secretSigning, stringToSign));
            return STS_ALGORITHM
                    + " Credential=" + secretId + "/" + credentialScope
                    + ", SignedHeaders=" + SIGNED_HEADERS
                    + ", Signature=" + signature;
        } catch (Exception e) {
            throw new IllegalStateException("签名生成失败: Authorization构造异常", e);
        }
    }

    private URI normalizeEndpoint(String endpoint) {
        String normalized = StringUtils.hasText(endpoint)
                ? endpoint.trim()
                : "https://sts.tencentcloudapi.com/";
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        return URI.create(normalized);
    }

    private int normalizeStsDuration(Integer durationSeconds) {
        int value = durationSeconds == null ? 1800 : durationSeconds;
        if (value < 900) {
            return 900;
        }
        if (value > 43200) {
            return 43200;
        }
        return value;
    }

    private byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(key, "HmacSHA256");
        mac.init(secretKeySpec);
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256Hex(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        return toHex(hashed);
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte aByte : bytes) {
            sb.append(String.format("%02x", aByte));
        }
        return sb.toString();
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }

    /**
     * STS 临时凭证。
     */
    @Getter
    @AllArgsConstructor
    public static class TemporaryCredential {
        private String secretId;
        private String secretKey;
        private String securityToken;
        private long expiredTime;
    }
}
