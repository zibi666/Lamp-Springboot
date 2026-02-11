package com.example.edog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户实体，对应 users 表。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("users")
public class User {

    /**
     * 内部主键ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 对外展示的用户ID。
     */
    private String userId;

    /**
     * 账号（唯一）。
     */
    private String phone;

    /**
     * BCrypt 加密后的密码。
     */
    private String password;

    /**
     * 用户昵称。
     */
    private String nickname;

    /**
     * 头像链接。
     */
    private String avatarUrl;

    /**
     * 微信 OpenID（预留）。
     */
    private String wxOpenid;
}
