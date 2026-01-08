package com.example.edog.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.edog.entity.UserAlarms;
import com.example.edog.mapper.UserAlarmsMapper;
import com.example.edog.service.UserAlarmsService;
import com.example.edog.dto.AlarmCreateRequest;
import com.example.edog.dto.AlarmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户闹钟业务服务实现
 */
@Slf4j
@Service
public class UserAlarmsServiceImpl extends ServiceImpl<UserAlarmsMapper, UserAlarms> implements UserAlarmsService {
    
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private static final Integer ALARM_TYPE_ONE_TIME = 1;
    private static final Integer ALARM_TYPE_RECURRING = 2;
    private static final Integer ALARM_STATUS_ENABLED = 1;
    
    /**
     * 周几中文描述映射
     */
    private static final String[] DAY_NAMES = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    
    @Override
    public long createAlarm(AlarmCreateRequest request) {
        try {
            // 参数验证
            if (request == null || request.getUserId() == null || request.getAlarmTime() == null) {
                log.warn("创建闹钟参数不完整");
                return -1;
            }
            
            if (request.getType() == null || (request.getType() != ALARM_TYPE_ONE_TIME && request.getType() != ALARM_TYPE_RECURRING)) {
                log.warn("闹钟类型不正确");
                return -1;
            }
            
            // 单次闹钟必须有targetDate
            if (ALARM_TYPE_ONE_TIME.equals(request.getType()) && (request.getTargetDate() == null || request.getTargetDate().isEmpty())) {
                log.warn("单次闹钟必须指定目标日期");
                return -1;
            }
            
            // 循环闹钟必须有repeatDays
            if (ALARM_TYPE_RECURRING.equals(request.getType()) && (request.getRepeatDays() == null || request.getRepeatDays().isEmpty())) {
                log.warn("循环闹钟必须指定重复日期");
                return -1;
            }
            
            UserAlarms alarm = new UserAlarms();
            alarm.setUserId(request.getUserId());
            
            // 解析时间
            try {
                LocalTime alarmTime = LocalTime.parse(request.getAlarmTime(), DateTimeFormatter.ofPattern("HH:mm[:ss]"));
                alarm.setAlarmTime(alarmTime);
            } catch (Exception e) {
                log.warn("时间格式错误: {}", request.getAlarmTime());
                return -1;
            }
            
            alarm.setType(request.getType());
            
            if (ALARM_TYPE_ONE_TIME.equals(request.getType())) {
                try {
                    LocalDate targetDate = LocalDate.parse(request.getTargetDate(), DATE_FORMATTER);
                    alarm.setTargetDate(targetDate);
                } catch (Exception e) {
                    log.warn("日期格式错误: {}", request.getTargetDate());
                    return -1;
                }
                alarm.setRepeatDays(null);
            } else {
                alarm.setRepeatDays(request.getRepeatDays());
                alarm.setTargetDate(null);
            }
            
            alarm.setTag(request.getTag() != null ? request.getTag() : "");
            alarm.setStatus(ALARM_STATUS_ENABLED);
            alarm.setCreatedAt(LocalDateTime.now());
            alarm.setUpdatedAt(LocalDateTime.now());
            
            // 保存到数据库
            this.save(alarm);
            log.info("闹钟创建成功，用户ID: {}, 闹钟ID: {}", request.getUserId(), alarm.getId());
            return alarm.getId();
            
        } catch (Exception e) {
            log.error("创建闹钟异常", e);
            return -1;
        }
    }
    
    @Override
    public boolean deleteAlarm(Long alarmId, String userId) {
        try {
            UserAlarms alarm = this.getById(alarmId);
            if (alarm == null) {
                log.warn("闹钟不存在，ID: {}", alarmId);
                return false;
            }
            
            // 检查权限：只能删除自己的闹钟
            if (!alarm.getUserId().equals(userId)) {
                log.warn("无权删除其他用户的闹钟，用户ID: {}, 闹钟ID: {}", userId, alarmId);
                return false;
            }
            
            this.removeById(alarmId);
            log.info("闹钟删除成功，闹钟ID: {}", alarmId);
            return true;
        } catch (Exception e) {
            log.error("删除闹钟异常", e);
            return false;
        }
    }
    
    @Override
    public List<AlarmResponse> getUserAlarms(String userId) {
        try {
            List<UserAlarms> alarms = this.baseMapper.selectUserAlarmsByUserId(userId);
            return convertToResponseList(alarms);
        } catch (Exception e) {
            log.error("获取用户闹钟异常", e);
            return new ArrayList<>();
        }
    }
    
    @Override
    public List<AlarmResponse> getAlarmsByTimeRange(String userId, LocalTime startTime, LocalTime endTime) {
        try {
            // 确保开始时间早于结束时间
            if (startTime.isAfter(endTime)) {
                LocalTime temp = startTime;
                startTime = endTime;
                endTime = temp;
            }
            
            List<UserAlarms> alarms = this.baseMapper.selectAlarmsByTimeRange(userId, startTime, endTime);
            return convertToResponseList(alarms);
        } catch (Exception e) {
            log.error("获取时间范围内的闹钟异常", e);
            return new ArrayList<>();
        }
    }
    
    @Override
    public boolean updateAlarmStatus(Long alarmId, String userId, Integer status) {
        try {
            UserAlarms alarm = this.getById(alarmId);
            if (alarm == null) {
                log.warn("闹钟不存在，ID: {}", alarmId);
                return false;
            }
            
            // 检查权限
            if (!alarm.getUserId().equals(userId)) {
                log.warn("无权更新其他用户的闹钟，用户ID: {}, 闹钟ID: {}", userId, alarmId);
                return false;
            }
            
            alarm.setStatus(status);
            alarm.setUpdatedAt(LocalDateTime.now());
            this.updateById(alarm);
            log.info("闹钟状态更新成功，闹钟ID: {}, 新状态: {}", alarmId, status);
            return true;
        } catch (Exception e) {
            log.error("更新闹钟状态异常", e);
            return false;
        }
    }
    
    /**
     * 将UserAlarms列表转换为AlarmResponse列表
     */
    private List<AlarmResponse> convertToResponseList(List<UserAlarms> alarms) {
        List<AlarmResponse> responses = new ArrayList<>();
        for (UserAlarms alarm : alarms) {
            responses.add(convertToResponse(alarm));
        }
        return responses;
    }
    
    /**
     * 将UserAlarms转换为AlarmResponse
     */
    private AlarmResponse convertToResponse(UserAlarms alarm) {
        String typeDesc = ALARM_TYPE_ONE_TIME.equals(alarm.getType()) ? "单次" : "循环";
        String repeatDaysDesc = null;
        
        // 如果是循环闹钟，生成中文描述
        if (ALARM_TYPE_RECURRING.equals(alarm.getType()) && alarm.getRepeatDays() != null) {
            repeatDaysDesc = generateRepeatDaysDesc(alarm.getRepeatDays());
        }
        
        return AlarmResponse.builder()
                .id(alarm.getId())
                .alarmTime(alarm.getAlarmTime())
                .type(alarm.getType())
                .typeDesc(typeDesc)
                .targetDate(alarm.getTargetDate())
                .repeatDays(alarm.getRepeatDays())
                .repeatDaysDesc(repeatDaysDesc)
                .tag(alarm.getTag())
                .status(alarm.getStatus())
                .createdAt(alarm.getCreatedAt() != null ? alarm.getCreatedAt().format(DATE_TIME_FORMATTER) : null)
                .updatedAt(alarm.getUpdatedAt() != null ? alarm.getUpdatedAt().format(DATE_TIME_FORMATTER) : null)
                .build();
    }
    
    /**
     * 根据数字字符串生成周几的中文描述
     * 例如 "1,2,3,4,5" -> "周一、周二、周三、周四、周五（工作日）"
     */
    private String generateRepeatDaysDesc(String repeatDays) {
        try {
            String[] days = repeatDays.split(",");
            StringBuilder desc = new StringBuilder();
            
            for (int i = 0; i < days.length; i++) {
                int dayIndex = Integer.parseInt(days[i].trim());
                if (dayIndex >= 1 && dayIndex <= 7) {
                    if (i > 0) {
                        desc.append("、");
                    }
                    desc.append(DAY_NAMES[dayIndex]);
                }
            }
            
            // 判断是否为工作日
            if ("1,2,3,4,5".equals(repeatDays)) {
                desc.append("（工作日）");
            } else if ("6,7".equals(repeatDays)) {
                desc.append("（周末）");
            } else if ("1,2,3,4,5,6,7".equals(repeatDays)) {
                desc.append("（每天）");
            }
            
            return desc.toString();
        } catch (Exception e) {
            log.warn("生成重复日期描述异常: {}", repeatDays);
            return repeatDays;
        }
    }
}
