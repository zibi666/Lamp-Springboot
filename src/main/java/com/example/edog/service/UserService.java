package com.example.edog.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.edog.dto.AuthLoginRequest;
import com.example.edog.dto.AuthRegisterRequest;
import com.example.edog.dto.UserProfileResponse;
import com.example.edog.dto.UserProfileUpdateRequest;
import com.example.edog.entity.User;

/**
 * 用户业务服务接口。
 */
public interface UserService extends IService<User> {

    /**
     * 用户注册。
     *
     * @param request 注册参数
     * @return 用户信息（脱敏）
     */
    UserProfileResponse register(AuthRegisterRequest request);

    /**
     * 用户登录。
     *
     * @param request 登录参数
     * @return 用户信息（脱敏）
     */
    UserProfileResponse login(AuthLoginRequest request);

    /**
     * 获取用户资料。
     *
     * @param userId 对外用户ID
     * @return 用户信息（脱敏）
     */
    UserProfileResponse getProfileByUserId(String userId);

    /**
     * 更新用户资料。
     *
     * @param userId 对外用户ID
     * @param request 更新参数
     * @return 更新后的用户信息（脱敏）
     */
    UserProfileResponse updateProfile(String userId, UserProfileUpdateRequest request);
}
