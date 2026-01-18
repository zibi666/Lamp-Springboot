-- 清理目标时间段内的历史数据
DELETE FROM health_data
WHERE upload_time >= '2026-01-01 00:00:00'
  AND upload_time <  '2026-01-18 00:00:00';
