package com.example.edog.controller;

import com.example.edog.dto.SleepSummaryResponse;
import com.example.edog.entity.SleepSummary;
import com.example.edog.service.SleepReportGeneratorService;
import com.example.edog.service.SleepSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 睡眠数据控制器
 * 提供睡眠数据汇总接口，供扣子AI调用
 * 数据来源：sleep_summary 表
 */
@Slf4j
@RestController
@RequestMapping("/api/sleep")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", maxAge = 3600)
public class SleepController {
    
    private final SleepSummaryService sleepSummaryService;
    private final SleepReportGeneratorService sleepReportGeneratorService;
    
    /**
     * 获取睡眠汇总数据
     * 
     * <p>根据查询日期从 sleep_summary 表获取该晚睡眠的汇总信息</p>
     * 
     * <p><b>示例请求：</b></p>
     * <pre>GET /api/sleep/summary?date=2026-01-15</pre>
     * 
     * <p><b>示例响应：</b></p>
     * <pre>
     * {
     *   "query_date": "2026-01-15",
     *   "sleep_time": "2026-01-15 22:30:00",
     *   "wake_time": "2026-01-16 07:15:00",
     *   "sample_count": 1050,
     *   "rem_duration_min": 90.0,
     *   "nrem_duration_min": 360.0,
     *   "total_sleep_min": 450.0,
     *   "avg_heart_rate": 32.5,
     *   "avg_breathing_rate": 8.5,
     *   "wake_count": 1,
     *   "wake_start_times": ["2026-01-16 03:20:00"],
     *   "message": null
     * }
     * </pre>
     * 
     * @param date 查询日期（必填，格式：YYYY-MM-DD）
     * @param userId 用户ID（可选，默认 user123）
     * @return 睡眠汇总数据
     */
    @GetMapping("/summary")
    public ResponseEntity<SleepSummaryResponse> getSleepSummary(
            @RequestParam(value = "date") String date,
            @RequestParam(value = "userId", required = false, defaultValue = "user123") String userId) {
        
        log.info("收到睡眠汇总查询请求: date={}, userId={}", date, userId);
        
        // 1. 参数校验：date 格式
        LocalDate queryDate;
        try {
            queryDate = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            log.warn("日期格式错误: {}", date);
            SleepSummaryResponse errorResponse = SleepSummaryResponse.builder()
                    .message("日期格式错误，请使用 YYYY-MM-DD 格式，例如：2026-01-15")
                    .build();
            return ResponseEntity.badRequest().body(errorResponse);
        }
        
        // 2. 从 sleep_summary 表查询数据（查不到会尝试自动生成）
        try {
            SleepSummaryResponse response = sleepSummaryService.getSleepSummaryByDateAndUser(queryDate, userId);
            log.info("睡眠汇总查询成功: date={}, userId={}, sampleCount={}", date, userId, response.getSampleCount());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("睡眠汇总查询失败: date={}, userId={}", date, userId, e);
            SleepSummaryResponse errorResponse = SleepSummaryResponse.builder()
                    .queryDate(queryDate)
                    .message("查询失败: " + e.getMessage())
                    .build();
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
    
    /**
     * 手动触发生成睡眠报告
     * 
     * <p>从 health_data 表读取数据，生成睡眠报告并保存到 sleep_summary 表，然后删除已使用的数据</p>
     *
     * @param userId 用户ID（可选，默认 user123）
     * @param date 查询日期（可选，默认昨天）
     * @return 生成结果
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateSleepReport(
            @RequestParam(value = "userId", required = false, defaultValue = "user123") String userId,
            @RequestParam(value = "date", required = false) String date) {
        
        log.info("收到手动生成睡眠报告请求: userId={}, date={}", userId, date);
        
        Map<String, Object> response = new HashMap<>();
        
        // 解析日期，默认为昨天
        LocalDate queryDate;
        if (date == null || date.trim().isEmpty()) {
            queryDate = LocalDate.now().minusDays(1);
        } else {
            try {
                queryDate = LocalDate.parse(date);
            } catch (DateTimeParseException e) {
                response.put("success", false);
                response.put("message", "日期格式错误，请使用 YYYY-MM-DD 格式");
                return ResponseEntity.badRequest().body(response);
            }
        }
        
        try {
            SleepSummary summary = sleepReportGeneratorService.generateReportManually(userId, queryDate);
            
            if (summary == null) {
                response.put("success", false);
                response.put("message", "该时间窗口内无睡眠数据");
                response.put("userId", userId);
                response.put("queryDate", queryDate.toString());
                return ResponseEntity.ok(response);
            }
            
            response.put("success", true);
            response.put("message", "睡眠报告生成成功");
            response.put("userId", userId);
            response.put("queryDate", queryDate.toString());
            response.put("totalSleepMin", summary.getTotalSleepMin());
            response.put("remDurationMin", summary.getRemDurationMin());
            response.put("nremDurationMin", summary.getNremDurationMin());
            response.put("wakeCount", summary.getWakeCount());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("生成睡眠报告失败: userId={}, date={}", userId, queryDate, e);
            response.put("success", false);
            response.put("message", "生成失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    /**
     * 触发检查所有用户起床状态并生成报告
     * 
     * @return 执行结果
     */
    @PostMapping("/check-and-generate")
    public ResponseEntity<Map<String, Object>> checkAndGenerateReports() {
        log.info("收到触发检查起床状态请求");
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            sleepReportGeneratorService.checkAndGenerateReportsForAllUsers();
            response.put("success", true);
            response.put("message", "已触发检查所有用户起床状态并生成报告");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("检查起床状态失败", e);
            response.put("success", false);
            response.put("message", "执行失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    /**
     * 获取一段时间内的睡眠报告汇总
     * 
     * <p>根据用户ID和日期范围获取该时间段内所有存在的睡眠报告</p>
     * 
     * <p><b>示例请求：</b></p>
     * <pre>GET /api/sleep/summaries?userId=user123&amp;startDate=2026-01-01&amp;endDate=2026-01-15</pre>
     * 
     * <p><b>示例响应（有数据）：</b></p>
     * <pre>
     * {
     *   "success": true,
     *   "message": "查询成功",
     *   "userId": "user123",
     *   "startDate": "2026-01-01",
     *   "endDate": "2026-01-15",
     *   "count": 10,
     *   "data": [
     *     {
     *       "query_date": "2026-01-05",
     *       "sleep_time": "2026-01-05 22:30:00",
     *       ...
     *     },
     *     ...
     *   ]
     * }
     * </pre>
     * 
     * <p><b>示例响应（无数据）：</b></p>
     * <pre>
     * {
     *   "success": false,
     *   "message": "所选日期段内无睡眠报告数据",
     *   "userId": "user123",
     *   "startDate": "2026-01-01",
     *   "endDate": "2026-01-15",
     *   "count": 0,
     *   "data": []
     * }
     * </pre>
     * 
     * @param userId 用户ID（必填）
     * @param startDate 开始日期（必填，格式：YYYY-MM-DD，包含）
     * @param endDate 结束日期（必填，格式：YYYY-MM-DD，包含）
     * @return 睡眠报告列表
     */
    @GetMapping("/summaries")
    public ResponseEntity<Map<String, Object>> getSleepSummariesByDateRange(
            @RequestParam(value = "userId") String userId,
            @RequestParam(value = "startDate") String startDate,
            @RequestParam(value = "endDate") String endDate) {
        
        log.info("收到睡眠报告范围查询请求: userId={}, startDate={}, endDate={}", userId, startDate, endDate);
        
        Map<String, Object> response = new HashMap<>();
        response.put("userId", userId);
        response.put("startDate", startDate);
        response.put("endDate", endDate);
        
        // 1. 参数校验：日期格式
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(startDate);
            end = LocalDate.parse(endDate);
        } catch (DateTimeParseException e) {
            log.warn("日期格式错误: startDate={}, endDate={}", startDate, endDate);
            response.put("success", false);
            response.put("message", "日期格式错误，请使用 YYYY-MM-DD 格式，例如：2026-01-15");
            response.put("count", 0);
            response.put("data", List.of());
            return ResponseEntity.badRequest().body(response);
        }
        
        // 2. 参数校验：日期范围
        if (start.isAfter(end)) {
            log.warn("开始日期晚于结束日期: startDate={}, endDate={}", startDate, endDate);
            response.put("success", false);
            response.put("message", "开始日期不能晚于结束日期");
            response.put("count", 0);
            response.put("data", List.of());
            return ResponseEntity.badRequest().body(response);
        }
        
        // 3. 查询数据
        try {
            List<SleepSummaryResponse> summaries = sleepSummaryService.getSleepSummariesByDateRange(userId, start, end);
            
            if (summaries.isEmpty()) {
                response.put("success", false);
                response.put("message", "所选日期段内无睡眠报告数据");
                response.put("count", 0);
                response.put("data", summaries);
                return ResponseEntity.ok(response);
            }
            
            response.put("success", true);
            response.put("message", "查询成功");
            response.put("count", summaries.size());
            response.put("data", summaries);
            
            log.info("睡眠报告范围查询成功: userId={}, startDate={}, endDate={}, count={}", 
                    userId, startDate, endDate, summaries.size());
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("睡眠报告范围查询失败: userId={}, startDate={}, endDate={}", userId, startDate, endDate, e);
            response.put("success", false);
            response.put("message", "查询失败: " + e.getMessage());
            response.put("count", 0);
            response.put("data", List.of());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
