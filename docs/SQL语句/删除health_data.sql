-- 清理目标时间段内的历史数据
DELETE FROM health_data
WHERE upload_time >= '1970-01-01 00:00:00'
  AND upload_time <  '2099-01-18 00:00:00';

-- 清理睡眠汇总数据
DELETE FROM sleep_summary
WHERE updated_at >= '1970-01-01'
  AND updated_at <  '2099-01-18';
