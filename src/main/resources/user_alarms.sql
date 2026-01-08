-- 创建用户闹钟表
CREATE TABLE IF NOT EXISTS user_alarms (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id VARCHAR(64) NOT NULL COMMENT '用户ID，关联用户表',
    
    -- 核心时间字段
    alarm_time TIME NOT NULL COMMENT '闹钟响铃时间，格式 HH:MM:00',
    
    -- 类型区分
    type TINYINT NOT NULL DEFAULT 1 COMMENT '类型：1-单次(OneTime), 2-循环(Recurring)',
    
    -- 规则字段
    target_date DATE DEFAULT NULL COMMENT '仅针对单次闹钟：具体的触发日期 YYYY-MM-DD',
    repeat_days VARCHAR(20) DEFAULT NULL COMMENT '仅针对循环闹钟：存储周几，如 "1,2,3,4,5" 代表周一到周五',
    
    -- 辅助字段
    tag VARCHAR(50) DEFAULT '' COMMENT '闹钟标签，如"起床"、"开会"',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-启用, 0-禁用',
    
    -- 基础字段
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 索引：加速按用户和时间查询（用于删除和触发扫描）
    INDEX idx_user_time (user_id, alarm_time),
    INDEX idx_status_time (status, alarm_time),
    INDEX idx_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户闹钟表，支持单次和循环闹钟';
