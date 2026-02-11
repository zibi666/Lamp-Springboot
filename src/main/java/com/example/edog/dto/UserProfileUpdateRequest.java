package com.example.edog.dto;

import lombok.Data;

/**
 * 更新用户资料请求参数。
 */
@Data
public class UserProfileUpdateRequest {

    /**
     * 昵称（可选）。
     */
    private String nickname;

    /**
     * 头像链接（可选）。
     */
    private String avatarUrl;

    /**
     * 微信 OpenID（可选，预留）。
     */
    private String wxOpenid;
}
