package com.example.edog.controller;

import com.example.edog.dto.AlarmCreateRequest;
import com.example.edog.dto.AlarmResponse;
import com.example.edog.service.UserAlarmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户闹钟API控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/alarms")
@CrossOrigin(origins = "*", maxAge = 3600)
public class AlarmsController {
    
    @Autowired
    private UserAlarmsService userAlarmsService;
    
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm[:ss]");
    
    /**
     * 创建闹钟
     * POST /api/alarms/create
     *
     * @param request 创建闹钟请求对象
     * @return 响应结果字符串
     */
    @PostMapping("/create")
    public ResponseEntity<String> createAlarm(@RequestBody AlarmCreateRequest request) {
        try {
            if (request.getUserId() == null || request.getUserId().isEmpty()) {
                return ResponseEntity.badRequest().body("用户ID不能为空");
            }
            
            String result = userAlarmsService.createAlarm(request);
            
            // 只要不是系统错误或参数错误导致无法处理，都返回200
            // 根据业务逻辑，"闹钟已存在"也属于正常处理流程（更新了时间）
            if (result.contains("异常") || result.contains("错误") || result.contains("不完整") || result.contains("不正确")) {
                 return ResponseEntity.badRequest().body(result);
            }
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("创建闹钟异常", e);
            return ResponseEntity.internalServerError().body("服务器异常: " + e.getMessage());
        }
    }
    
    /**
     * 删除闹钟
     * DELETE /api/alarms/{alarmId}
     *
     * @param alarmId 闹钟ID
     * @param userId 用户ID
     * @return 响应结果
     */
    @DeleteMapping("/{alarmId}")
    public ResponseEntity<Map<String, Object>> deleteAlarm(
            @PathVariable Long alarmId,
            @RequestParam String userId) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            if (userId == null || userId.isEmpty()) {
                response.put("code", 400);
                response.put("message", "用户ID不能为空");
                response.put("success", false);
                return ResponseEntity.badRequest().body(response);
            }
            
            boolean success = userAlarmsService.deleteAlarm(alarmId, userId);
            
            if (success) {
                response.put("code", 200);
                response.put("message", "闹钟删除成功");
                response.put("success", true);
                return ResponseEntity.ok(response);
            } else {
                response.put("code", 400);
                response.put("message", "闹钟删除失败");
                response.put("success", false);
                return ResponseEntity.badRequest().body(response);
            }
        } catch (Exception e) {
            log.error("删除闹钟异常", e);
            response.put("code", 500);
            response.put("message", "服务器异常: " + e.getMessage());
            response.put("success", false);
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    /**
     * 获取用户所有闹钟
     * GET /api/alarms/list/{userId}
     *
     * @param userId 用户ID
     * @return 闹钟列表
     */
    @GetMapping("/list/{userId}")
    public ResponseEntity<Map<String, Object>> getUserAlarms(@PathVariable String userId) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            List<AlarmResponse> alarms = userAlarmsService.getUserAlarms(userId);
            
            response.put("code", 200);
            response.put("message", "查询成功");
            response.put("success", true);
            response.put("data", Map.of(
                    "total", alarms.size(),
                    "alarms", alarms
            ));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取用户闹钟异常", e);
            response.put("code", 500);
            response.put("message", "服务器异常: " + e.getMessage());
            response.put("success", false);
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    /**
     * 获取指定时间范围内的闹钟列表
     * GET /api/alarms/range/{userId}
     *
     * @param userId 用户ID
     * @param startTime 范围开始时间 (HH:MM 或 HH:MM:SS)
     * @param endTime 范围结束时间 (HH:MM 或 HH:MM:SS)
     * @return 闹钟列表
     */
    @GetMapping("/range/{userId}")
    public ResponseEntity<Map<String, Object>> getAlarmsByTimeRange(
            @PathVariable String userId,
            @RequestParam String startTime,
            @RequestParam String endTime) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            LocalTime start = LocalTime.parse(startTime, TIME_FORMATTER);
            LocalTime end = LocalTime.parse(endTime, TIME_FORMATTER);
            
            List<AlarmResponse> alarms = userAlarmsService.getAlarmsByTimeRange(userId, start, end);
            
            response.put("code", 200);
            response.put("message", "查询成功");
            response.put("success", true);
            response.put("data", Map.of(
                    "startTime", startTime,
                    "endTime", endTime,
                    "total", alarms.size(),
                    "alarms", alarms
            ));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("获取时间范围内的闹钟异常", e);
            response.put("code", 500);
            response.put("message", "服务器异常: " + e.getMessage());
            response.put("success", false);
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    /**
     * 启用/禁用闹钟
     * PUT /api/alarms/{alarmId}/status
     *
     * @param alarmId 闹钟ID
     * @param userId 用户ID
     * @param status 状态：1-启用, 0-禁用
     * @return 响应结果
     */
    @PutMapping("/{alarmId}/status")
    public ResponseEntity<Map<String, Object>> updateAlarmStatus(
            @PathVariable Long alarmId,
            @RequestParam String userId,
            @RequestParam Integer status) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            if (userId == null || userId.isEmpty()) {
                response.put("code", 400);
                response.put("message", "用户ID不能为空");
                response.put("success", false);
                return ResponseEntity.badRequest().body(response);
            }
            
            if (status == null || (status != 0 && status != 1)) {
                response.put("code", 400);
                response.put("message", "状态值只能为 0 或 1");
                response.put("success", false);
                return ResponseEntity.badRequest().body(response);
            }
            
            boolean success = userAlarmsService.updateAlarmStatus(alarmId, userId, status);
            
            if (success) {
                response.put("code", 200);
                response.put("message", "闹钟状态更新成功");
                response.put("success", true);
                return ResponseEntity.ok(response);
            } else {
                response.put("code", 400);
                response.put("message", "闹钟状态更新失败");
                response.put("success", false);
                return ResponseEntity.badRequest().body(response);
            }
        } catch (Exception e) {
            log.error("更新闹钟状态异常", e);
            response.put("code", 500);
            response.put("message", "服务器异常: " + e.getMessage());
            response.put("success", false);
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
