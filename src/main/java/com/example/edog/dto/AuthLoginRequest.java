package com.example.edog.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * 登录请求参数。
 */
@Data
public class AuthLoginRequest {

    /**
     * 账号（兼容 phone/account 两种入参名）。
     */
    @JsonAlias("account")
    private String phone;

    /**
     * 明文密码（服务端校验后对比哈希）。
     */
    private String password;
}
