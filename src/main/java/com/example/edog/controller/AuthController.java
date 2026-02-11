package com.example.edog.controller;

import com.example.edog.dto.AuthLoginRequest;
import com.example.edog.dto.AuthRegisterRequest;
import com.example.edog.dto.UserProfileResponse;
import com.example.edog.dto.UserProfileUpdateRequest;
import com.example.edog.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户认证控制器。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*", maxAge = 3600)
public class AuthController {

    @Autowired
    private UserService userService;

    /**
     * 注册。
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody AuthRegisterRequest request) {
        try {
            UserProfileResponse profile = userService.register(request);
            return ResponseEntity.ok(buildResponse(200, "注册成功", true, profile));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(buildResponse(400, e.getMessage(), false, null));
        } catch (Exception e) {
            log.error("注册异常", e);
            return ResponseEntity.internalServerError().body(buildResponse(500, "服务器异常: " + e.getMessage(), false, null));
        }
    }

    /**
     * 登录。
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody AuthLoginRequest request) {
        try {
            UserProfileResponse profile = userService.login(request);
            return ResponseEntity.ok(buildResponse(200, "登录成功", true, profile));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(buildResponse(400, e.getMessage(), false, null));
        } catch (Exception e) {
            log.error("登录异常", e);
            return ResponseEntity.internalServerError().body(buildResponse(500, "服务器异常: " + e.getMessage(), false, null));
        }
    }

    /**
     * 按 userId 获取资料。
     */
    @GetMapping("/profile/{userId}")
    public ResponseEntity<Map<String, Object>> profile(@PathVariable String userId) {
        try {
            UserProfileResponse profile = userService.getProfileByUserId(userId);
            return ResponseEntity.ok(buildResponse(200, "查询成功", true, profile));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(buildResponse(400, e.getMessage(), false, null));
        } catch (Exception e) {
            log.error("查询用户资料异常", e);
            return ResponseEntity.internalServerError().body(buildResponse(500, "服务器异常: " + e.getMessage(), false, null));
        }
    }

    /**
     * 更新用户资料。
     */
    @PutMapping("/profile/{userId}")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @PathVariable String userId,
            @RequestBody UserProfileUpdateRequest request) {
        try {
            UserProfileResponse profile = userService.updateProfile(userId, request);
            return ResponseEntity.ok(buildResponse(200, "更新成功", true, profile));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(buildResponse(400, e.getMessage(), false, null));
        } catch (Exception e) {
            log.error("更新用户资料异常", e);
            return ResponseEntity.internalServerError().body(buildResponse(500, "服务器异常: " + e.getMessage(), false, null));
        }
    }

    private Map<String, Object> buildResponse(int code, String message, boolean success, Object data) {
        Map<String, Object> response = new HashMap<>();
        response.put("code", code);
        response.put("message", message);
        response.put("success", success);
        if (data != null) {
            response.put("data", data);
        }
        return response;
    }
}
