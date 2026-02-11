package com.example.edog.controller;

import com.example.edog.dto.SleepSummaryResponse;
import com.example.edog.entity.HealthData;
import com.example.edog.entity.SleepSummary;
import com.example.edog.service.HealthDataService;
import com.example.edog.service.SleepReportGeneratorService;
import com.example.edog.service.SleepSummaryService;
import com.example.edog.utils.CozeAPI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
	
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
    
    private static final String DEFAULT_USER_ID = "user123";
    private static final double SAMPLE_INTERVAL_MIN = 0.5;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HealthDataService healthDataService;
    private final SleepSummaryService sleepSummaryService;
    private final SleepReportGeneratorService sleepReportGeneratorService;
    private final CozeAPI cozeAPI;
    
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
     * 获取睡眠详细数据（睡眠窗口内的所有样本 + 分期条数据）
     *
     * <p>用于前端分期图表渲染，自动查询所选日期的完整睡眠窗口数据</p>
     *
     * @param date 查询日期（必填，格式：YYYY-MM-DD）
     * @param userId 用户ID（可选，默认 user123）
     * @return 详细睡眠数据
     */
    @GetMapping("/details")
    public ResponseEntity<Map<String, Object>> getSleepDetails(
            @RequestParam(value = "date") String date,
            @RequestParam(value = "userId", required = false, defaultValue = DEFAULT_USER_ID) String userId) {
        Map<String, Object> response = new HashMap<>();
        String normalizedUserId = normalizeUserId(userId);

        LocalDate queryDate;
        try {
            queryDate = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            response.put("success", false);
            response.put("message", "日期格式错误，请使用 YYYY-MM-DD 格式，例如：2026-01-15");
            return ResponseEntity.badRequest().body(response);
        }

        try {
            LocalDateTime windowStart = healthDataService.getSleepWindowStart(queryDate);
            LocalDateTime windowEnd = healthDataService.getSleepWindowEnd(queryDate);
            List<HealthData> dataList = healthDataService.getSleepWindowDataByDateAndUser(queryDate, normalizedUserId);

            response.put("success", true);
            response.put("query_date", queryDate.toString());
            response.put("user_id", normalizedUserId);
            response.put("window_start", formatDateTime(windowStart));
            response.put("window_end", formatDateTime(windowEnd));
            response.put("sample_count", dataList != null ? dataList.size() : 0);
            response.put("stage_bars", buildStageBars(dataList));
            response.put("samples", buildSampleData(dataList));
            response.put("message", (dataList == null || dataList.isEmpty()) ? "该日期睡眠窗口内无详细数据" : "查询成功");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("查询睡眠详细数据失败: date={}, userId={}", date, normalizedUserId, e);
            response.put("success", false);
            response.put("message", "查询失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * 生成AI睡眠报告分析（调用扣子 Coze）
     *
     * <p>前端可传入 date + userId，后端拼接问题词并调用智能体生成自然语言分析</p>
     *
     * @param request 请求体
     * @return AI报告文本
     */
    @PostMapping("/ai-report")
    public ResponseEntity<Map<String, Object>> generateAiSleepReport(@RequestBody AiSleepReportRequest request) {
        Map<String, Object> response = new HashMap<>();

        String dateText = request != null ? request.getDate() : null;
        String userId = request != null ? request.getUserId() : null;
        String question = request != null ? request.getQuestion() : null;

        String normalizedUserId = (userId == null || userId.trim().isEmpty()) ? "user123" : userId.trim();
        LocalDate queryDate;
        try {
            queryDate = LocalDate.parse(dateText);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "日期格式错误，请使用 YYYY-MM-DD");
            return ResponseEntity.badRequest().body(response);
        }

        String normalizedQuestion = (question == null || question.trim().isEmpty())
                ? String.format("我%d月%d号睡得咋样", queryDate.getMonthValue(), queryDate.getDayOfMonth())
                : question.trim();
        String finalQuestion = normalizedQuestion + "。我的userId是" + normalizedUserId + "。";

        log.info("收到AI睡眠报告请求: date={}, userId={}, question={}", queryDate, normalizedUserId, normalizedQuestion);

        try {
            String conversationId = cozeAPI.createConversation();
            String[] cozeResult = cozeAPI.CozeRequest(finalQuestion, normalizedUserId, null, null, true, conversationId);
            String reportText = (cozeResult != null && cozeResult.length > 1 && cozeResult[1] != null)
                    ? cozeResult[1].trim() : "";

            if (reportText.isEmpty()) {
                response.put("success", false);
                response.put("message", "AI未返回有效报告，请稍后重试");
                return ResponseEntity.internalServerError().body(response);
            }

            response.put("success", true);
            response.put("message", "生成成功");
            response.put("query_date", queryDate.toString());
            response.put("user_id", normalizedUserId);
            response.put("question", normalizedQuestion);
            response.put("report_text", reportText);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("生成AI睡眠报告失败: date={}, userId={}", queryDate, normalizedUserId, e);
            response.put("success", false);
            response.put("message", "生成失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
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
            response.put("lightSleepDurationMin", summary.getLightSleepDurationMin());
            response.put("deepSleepDurationMin", summary.getDeepSleepDurationMin());
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

    private String normalizeUserId(String userId) {
        return (userId == null || userId.trim().isEmpty()) ? DEFAULT_USER_ID : userId.trim();
    }

    private String normalizeStageType(String status) {
        if (status == null || status.trim().isEmpty()) {
            return "wake";
        }
        String normalized = status.trim().toLowerCase();
        if ("deep".equals(normalized) || "deepsleep".equals(normalized) || "深睡".equals(normalized)) {
            return "deep";
        }
        if ("light".equals(normalized) || "lightsleep".equals(normalized) || "浅睡".equals(normalized)) {
            return "light";
        }
        if ("rem".equals(normalized) || "快速眼动".equals(normalized) || "rapid-eye-movement".equals(normalized)) {
            return "rem";
        }
        return "wake";
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private List<Map<String, Object>> buildSampleData(List<HealthData> dataList) {
        List<Map<String, Object>> samples = new ArrayList<>();
        if (dataList == null || dataList.isEmpty()) {
            return samples;
        }

        for (HealthData item : dataList) {
            Map<String, Object> sample = new HashMap<>();
            sample.put("id", item.getId());
            sample.put("upload_time", formatDateTime(item.getUploadTime()));
            sample.put("heart_rate", item.getHeartRate());
            sample.put("breathing_rate", item.getBreathingRate());
            sample.put("motion_index", item.getMotionIndex());
            sample.put("sleep_status", normalizeStageType(item.getSleepStatus()));
            samples.add(sample);
        }
        return samples;
    }

    private List<Map<String, Object>> buildStageBars(List<HealthData> dataList) {
        List<Map<String, Object>> bars = new ArrayList<>();
        if (dataList == null || dataList.isEmpty()) {
            return bars;
        }

        int stageIndex = 0;
        String currentType = normalizeStageType(dataList.get(0).getSleepStatus());
        LocalDateTime segmentStart = dataList.get(0).getUploadTime();
        LocalDateTime segmentLast = segmentStart;
        int segmentCount = 1;

        for (int i = 1; i < dataList.size(); i += 1) {
            HealthData current = dataList.get(i);
            String currentStatus = normalizeStageType(current.getSleepStatus());
            LocalDateTime currentTime = current.getUploadTime();
            if (currentTime != null) {
                segmentLast = currentTime;
            }

            if (currentStatus.equals(currentType)) {
                segmentCount++;
                continue;
            }

            stageIndex = appendStageBar(bars, stageIndex, currentType, segmentStart, segmentLast, segmentCount);
            currentType = currentStatus;
            segmentStart = currentTime;
            segmentLast = currentTime;
            segmentCount = 1;
        }

        appendStageBar(bars, stageIndex, currentType, segmentStart, segmentLast, segmentCount);
        return bars;
    }

    private int appendStageBar(List<Map<String, Object>> bars, int stageIndex, String type,
                               LocalDateTime start, LocalDateTime last, int sampleCount) {
        if (start == null || sampleCount <= 0) {
            return stageIndex;
        }

        LocalDateTime safeLast = last != null ? last : start;
        LocalDateTime end = safeLast.plusSeconds(30);
        double durationMin = round2(sampleCount * SAMPLE_INTERVAL_MIN);

        Map<String, Object> segment = new HashMap<>();
        segment.put("id", String.format("stage-%s-%d", type, stageIndex));
        segment.put("type", type);
        segment.put("start_time", formatDateTime(start));
        segment.put("end_time", formatDateTime(end));
        segment.put("duration_min", durationMin);
        segment.put("sample_count", sampleCount);
        bars.add(segment);
        return stageIndex + 1;
    }

    @lombok.Data
    public static class AiSleepReportRequest {
        /**
         * 查询日期（YYYY-MM-DD）
         */
        private String date;

        /**
         * 用户ID（可选，默认 user123）
         */
        private String userId;

        /**
         * 可选自定义问题，不传则按默认模板构造
         */
        private String question;
    }
}
