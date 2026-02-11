package com.example.edog.configurer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * COS 上传相关配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cos")
public class CosProperties {

    /**
     * 存储桶名称（需包含 appid 后缀）。
     * 示例：examplebucket-1250000000
     */
    private String bucket;

    /**
     * COS 地域。
     * 示例：ap-guangzhou
     */
    private String region;

    /**
     * 主账号/子账号 SecretId（仅后端使用）。
     */
    private String secretId;

    /**
     * 主账号/子账号 SecretKey（仅后端使用）。
     */
    private String secretKey;

    /**
     * 是否启用 STS 临时凭证。
     */
    private boolean useTempCredential = false;

    /**
     * STS 角色 ARN（启用临时凭证时必填）。
     */
    private String roleArn;

    /**
     * STS 会话名称。
     */
    private String roleSessionName = "lamp-avatar-upload";

    /**
     * STS 外部 ID（可选）。
     */
    private String externalId;

    /**
     * STS 临时密钥有效期，单位秒，默认 1800。
     */
    private Integer stsDurationSeconds = 1800;

    /**
     * Policy 有效期，单位秒，默认 300（5 分钟）。
     */
    private Integer policyExpireSeconds = 300;

    /**
     * 上传文件大小上限，单位字节，默认 2MB。
     */
    private Long maxFileSizeBytes = 2L * 1024 * 1024;

    /**
     * 对外访问域名（可选，不填则使用 bucket + region 拼接）。
     */
    private String publicHost;

    /**
     * 上传域名（可选，不填则使用 bucket + region 拼接）。
     */
    private String uploadHost;

    /**
     * 是否要求登录态（通过请求头 userId）校验。
     */
    private boolean requireLogin = false;

    /**
     * 登录用户ID请求头名。
     */
    private String loginUserHeader = "X-User-Id";

    /**
     * STS 接口地址。
     */
    private String stsEndpoint = "https://sts.tencentcloudapi.com/";
}
