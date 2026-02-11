package com.example.edog.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * 注册请求参数。
 */
@Data
public class AuthRegisterRequest {

    /**
     * 账号（兼容 phone/account 两种入参名）。
     */
    @JsonAlias("account")
    private String phone;

    /**
     * 明文密码。
     */
    private String password;

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
