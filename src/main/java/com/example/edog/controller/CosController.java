package com.example.edog.controller;

import com.example.edog.configurer.CosProperties;
import com.example.edog.dto.CosPostPolicyData;
import com.example.edog.service.CosPostPolicyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * COS 上传相关接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/cos")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", maxAge = 3600)
public class CosController {

    private final CosPostPolicyService cosPostPolicyService;
    private final CosProperties cosProperties;

    /**
     * 获取小程序直传 COS 所需策略。
     */
    @GetMapping("/post-policy")
    public ResponseEntity<Map<String, Object>> getPostPolicy(
            @RequestParam("scene") String scene,
            @RequestParam("userId") String userId,
            @RequestParam("fileExt") String fileExt,
            @RequestParam("contentType") String contentType,
            HttpServletRequest request) {
        try {
            String headerName = cosProperties.getLoginUserHeader();
            String loginUserId = request.getHeader(headerName);

            CosPostPolicyData data = cosPostPolicyService.generatePostPolicy(
                    scene, userId, fileExt, contentType, loginUserId
            );
            return ResponseEntity.ok(buildResponse(200, "获取成功", true, data));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(buildResponse(401, e.getMessage(), false, null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(buildResponse(400, e.getMessage(), false, null));
        } catch (IllegalStateException e) {
            log.error("生成COS上传策略失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildResponse(500, e.getMessage(), false, null));
        } catch (Exception e) {
            log.error("生成COS上传策略异常", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(buildResponse(500, "签名生成失败", false, null));
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
