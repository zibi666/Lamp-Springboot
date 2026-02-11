package com.example.edog.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * COS 直传策略响应数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CosPostPolicyData {

    /**
     * COS 上传地址。
     */
    @JsonProperty("url")
    private String url;

    /**
     * 本次上传对象 key。
     */
    @JsonProperty("cosKey")
    private String cosKey;

    /**
     * base64 编码策略。
     */
    @JsonProperty("policy")
    private String policy;

    /**
     * 签名算法。
     */
    @JsonProperty("qSignAlgorithm")
    private String qSignAlgorithm;

    /**
     * SecretId 或 TmpSecretId。
     */
    @JsonProperty("qAk")
    private String qAk;

    /**
     * 签名时间窗（start;end）。
     */
    @JsonProperty("qKeyTime")
    private String qKeyTime;

    /**
     * policy 对应签名。
     */
    @JsonProperty("qSignature")
    private String qSignature;

    /**
     * 临时凭证 token（使用 STS 时返回）。
     */
    @JsonProperty("securityToken")
    private String securityToken;

    /**
     * 公网访问域名。
     */
    @JsonProperty("publicHost")
    private String publicHost;

    /**
     * 上传成功后的公网 URL。
     */
    @JsonProperty("publicUrl")
    private String publicUrl;
}
