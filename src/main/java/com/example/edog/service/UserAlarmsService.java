package com.example.edog.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.edog.entity.UserAlarms;
import com.example.edog.dto.AlarmCreateRequest;
import com.example.edog.dto.AlarmResponse;

import java.time.LocalTime;
import java.util.List;

/**
 * 用户闹钟业务服务接口
 */
public interface UserAlarmsService extends IService<UserAlarms> {
    
    /**
     * 创建闹钟
     *
     * @param request 创建闹钟请求对象
     * @return 返回操作结果消息（如"创建成功"、"闹钟已存在"等）
     */
    String createAlarm(AlarmCreateRequest request);
    
    /**
     * 删除闹钟
     *
     * @param alarmId 闹钟ID
     * @param userId 用户ID（用于校验权限）
     * @return 删除成功返回true
     */
    boolean deleteAlarm(Long alarmId, String userId);
    
    /**
     * 获取用户所有闹钟
     *
     * @param userId 用户ID
     * @return 闹钟列表（按时间排序）
     */
    List<AlarmResponse> getUserAlarms(String userId);
    

    /**
     * 获取指定时间范围内的闹钟列表
     *
     * @param userId 用户ID
     * @param startTime 范围开始时间 (HH:MM:SS)
     * @param endTime 范围结束时间 (HH:MM:SS)
     * @return 闹钟列表
     */
    List<AlarmResponse> getAlarmsByTimeRange(String userId, LocalTime startTime, LocalTime endTime);
    
    /**
     * 启用/禁用闹钟
     *
     * @param alarmId 闹钟ID
     * @param userId 用户ID（用于校验权限）
     * @param status 状态：1-启用, 0-禁用
     * @return 操作成功返回true
     */
    boolean updateAlarmStatus(Long alarmId, String userId, Integer status);

    /**
     * 更新闹钟状态（state 字段语义与 status 一致，便于前端直接调用）
     *
     * @param alarmId 闹钟ID
     * @param userId 用户ID（用于校验权限）
     * @param state 状态：1-启用, 0-禁用
     * @return 操作成功返回true
     */
    boolean updateAlarmState(Long alarmId, String userId, Integer state);
}
