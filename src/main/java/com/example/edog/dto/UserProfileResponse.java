package com.example.edog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 返回给前端的用户信息，不包含密码。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileResponse {

    /**
     * 对外用户ID。
     */
    private String userId;

    /**
     * 账号。
     */
    private String phone;

    /**
     * 昵称。
     */
    private String nickname;

    /**
     * 头像地址。
     */
    private String avatarUrl;

    /**
     * 微信OpenID（预留字段）。
     */
    private String wxOpenid;
}
