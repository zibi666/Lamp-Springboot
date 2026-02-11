package com.example.edog.service;

import com.example.edog.configurer.CosProperties;
import com.example.edog.dto.CosPostPolicyData;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 生成 COS POST Object 直传策略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CosPostPolicyService {

    private static final String SCENE_AVATAR = "avatar";
    private static final String SIGN_ALGORITHM = "sha1";
    private static final Pattern USER_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Map<String, Set<String>> ALLOWED_CONTENT_TYPES = buildAllowedContentTypes();

    private final CosProperties cosProperties;
    private final TencentStsService tencentStsService;
    private final ObjectMapper objectMapper;

    /**
     * 生成小程序上传所需策略字段。
     */
    public CosPostPolicyData generatePostPolicy(String scene, String userId, String fileExt,
                                                String contentType, String loginUserId) {
        validateScene(scene);
        String normalizedUserId = validateUserId(userId);
        validateLoginUser(loginUserId, normalizedUserId);
        String normalizedExt = validateFileExt(fileExt);
        String normalizedContentType = validateContentType(normalizedExt, contentType);

        String bucket = requireConfig(cosProperties.getBucket(), "COS配置缺失: cos.bucket");
        String region = requireConfig(cosProperties.getRegion(), "COS配置缺失: cos.region");

        Credential credential = resolveCredential();

        long nowEpochSeconds = Instant.now().getEpochSecond();
        int expireSeconds = normalizePolicyExpireSeconds(cosProperties.getPolicyExpireSeconds());
        long expireEpochSeconds = nowEpochSeconds + expireSeconds;

        String qKeyTime = nowEpochSeconds + ";" + expireEpochSeconds;
        String cosKey = buildCosKey(normalizedUserId, normalizedExt, nowEpochSeconds);

        long maxFileSize = normalizeMaxFileSize(cosProperties.getMaxFileSizeBytes());
        PolicyPayload policyPayload = buildPolicy(bucket, cosKey, normalizedContentType, qKeyTime,
                credential.getSecretId(), credential.getSecurityToken(), maxFileSize, expireEpochSeconds);
        String qSignature = signPolicy(policyPayload.getRawPolicy(), qKeyTime, credential.getSecretKey());

        String defaultHost = buildDefaultCosHost(bucket, region);
        String uploadHost = StringUtils.hasText(cosProperties.getUploadHost())
                ? cosProperties.getUploadHost().trim()
                : defaultHost;
        String publicHost = StringUtils.hasText(cosProperties.getPublicHost())
                ? cosProperties.getPublicHost().trim()
                : defaultHost;

        return CosPostPolicyData.builder()
                .url("https://" + uploadHost)
                .cosKey(cosKey)
                .policy(policyPayload.getBase64Policy())
                .qSignAlgorithm(SIGN_ALGORITHM)
                .qAk(credential.getSecretId())
                .qKeyTime(qKeyTime)
                .qSignature(qSignature)
                .securityToken(credential.getSecurityToken())
                .publicHost(publicHost)
                .publicUrl("https://" + publicHost + "/" + cosKey)
                .build();
    }

    private Credential resolveCredential() {
        if (cosProperties.isUseTempCredential()) {
            TencentStsService.TemporaryCredential temp = tencentStsService.getTemporaryCredential();
            return new Credential(temp.getSecretId(), temp.getSecretKey(), temp.getSecurityToken());
        }
        String secretId = requireConfig(cosProperties.getSecretId(), "COS配置缺失: cos.secret-id");
        String secretKey = requireConfig(cosProperties.getSecretKey(), "COS配置缺失: cos.secret-key");
        return new Credential(secretId, secretKey, null);
    }

    private PolicyPayload buildPolicy(String bucket, String cosKey, String contentType, String qKeyTime,
                                      String qAk, String securityToken, long maxFileSize,
                                      long expireEpochSeconds) {
        try {
            Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("expiration", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(expireEpochSeconds)));

            List<Object> conditions = new ArrayList<>();
            conditions.add(singleCondition("bucket", bucket));
            conditions.add(singleCondition("key", cosKey));
            conditions.add(singleCondition("Content-Type", contentType));
            conditions.add(singleCondition("q-sign-algorithm", SIGN_ALGORITHM));
            conditions.add(singleCondition("q-ak", qAk));
            conditions.add(singleCondition("q-sign-time", qKeyTime));
            if (StringUtils.hasText(securityToken)) {
                conditions.add(singleCondition("x-cos-security-token", securityToken));
            }
            conditions.add(Arrays.asList("content-length-range", 1, maxFileSize));
            policy.put("conditions", conditions);

            String rawPolicy = objectMapper.writeValueAsString(policy);
            String base64Policy = Base64.getEncoder().encodeToString(rawPolicy.getBytes(StandardCharsets.UTF_8));
            return new PolicyPayload(rawPolicy, base64Policy);
        } catch (Exception e) {
            log.error("构建COS policy失败", e);
            throw new IllegalStateException("签名生成失败");
        }
    }

    private Map<String, String> singleCondition(String key, String value) {
        Map<String, String> condition = new LinkedHashMap<>();
        condition.put(key, value);
        return condition;
    }

    private String signPolicy(String policyText, String qKeyTime, String secretKey) {
        try {
            String signKey = hmacSha1Hex(secretKey, qKeyTime);
            String stringToSign = sha1Hex(policyText);
            return hmacSha1Hex(signKey, stringToSign);
        } catch (Exception e) {
            log.error("生成COS签名失败", e);
            throw new IllegalStateException("签名生成失败");
        }
    }

    private String buildCosKey(String userId, String ext, long timestamp) {
        return "avatars/u_" + userId + "/" + timestamp + "_" + randomLowerHex(8) + "." + ext;
    }

    private String randomLowerHex(int len) {
        String alphabet = "0123456789abcdef";
        StringBuilder sb = new StringBuilder(len);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private String hmacSha1Hex(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1");
        mac.init(secretKey);
        byte[] hashed = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return toHex(hashed);
    }

    private String sha1Hex(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
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

    private void validateScene(String scene) {
        String value = normalize(scene);
        if (!SCENE_AVATAR.equals(value)) {
            throw new IllegalArgumentException("scene 仅支持 avatar");
        }
    }

    private String validateUserId(String userId) {
        String value = normalize(userId);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (!USER_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("userId 格式不合法");
        }
        return value;
    }

    private void validateLoginUser(String loginUserId, String userId) {
        String loginUser = normalize(loginUserId);
        if (cosProperties.isRequireLogin() && !StringUtils.hasText(loginUser)) {
            throw new SecurityException("未登录");
        }
        if (StringUtils.hasText(loginUser) && !Objects.equals(loginUser, userId)) {
            throw new SecurityException("登录用户与userId不一致");
        }
    }

    private String validateFileExt(String fileExt) {
        String value = normalize(fileExt);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("fileExt 不能为空");
        }
        if (value.startsWith(".")) {
            value = value.substring(1);
        }
        value = value.toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.containsKey(value)) {
            throw new IllegalArgumentException("仅支持 jpg/jpeg/png/webp/gif 图片上传");
        }
        return value;
    }

    private String validateContentType(String fileExt, String contentType) {
        String value = normalize(contentType);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("contentType 不能为空");
        }
        int index = value.indexOf(';');
        if (index > 0) {
            value = value.substring(0, index).trim();
        }
        value = value.toLowerCase(Locale.ROOT);
        Set<String> allowed = ALLOWED_CONTENT_TYPES.get(fileExt);
        if (allowed == null || !allowed.contains(value)) {
            throw new IllegalArgumentException("contentType 与 fileExt 不匹配");
        }
        return value;
    }

    private long normalizeMaxFileSize(Long maxFileSizeBytes) {
        if (maxFileSizeBytes == null || maxFileSizeBytes <= 0) {
            return 2L * 1024 * 1024;
        }
        return maxFileSizeBytes;
    }

    private int normalizePolicyExpireSeconds(Integer expireSeconds) {
        if (expireSeconds == null || expireSeconds <= 0) {
            return 300;
        }
        return Math.min(expireSeconds, 1800);
    }

    private String buildDefaultCosHost(String bucket, String region) {
        return bucket + ".cos." + region + ".myqcloud.com";
    }

    private String requireConfig(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(message);
        }
        return value.trim();
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<String, Set<String>> buildAllowedContentTypes() {
        Map<String, Set<String>> map = new HashMap<>();
        map.put("jpg", new HashSet<>(Arrays.asList("image/jpeg", "image/jpg")));
        map.put("jpeg", new HashSet<>(Arrays.asList("image/jpeg", "image/jpg")));
        map.put("png", Collections.singleton("image/png"));
        map.put("webp", Collections.singleton("image/webp"));
        map.put("gif", Collections.singleton("image/gif"));
        return Collections.unmodifiableMap(map);
    }

    @Getter
    @AllArgsConstructor
    private static class Credential {
        private String secretId;
        private String secretKey;
        private String securityToken;
    }

    @Getter
    @AllArgsConstructor
    private static class PolicyPayload {
        private String rawPolicy;
        private String base64Policy;
    }
}
