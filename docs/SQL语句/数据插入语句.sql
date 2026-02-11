-- 查询日期范围：2026-01-20 ~ 2026-02-08
-- 对齐后端睡眠汇总窗口规则：queryDate 当天 18:00 到次日 12:00
-- 因此数据生成范围需延伸到 2026-02-09 11:59:30（用于保障 2026-02-08 的次日上午窗口）
-- 可重复执行：先清理目标区间，避免重复数据影响汇总
DELETE FROM health_data
WHERE user_id = 'user123'
  AND upload_time >= '2026-01-20 00:00:00'
  AND upload_time <= '2026-02-09 11:59:30';

DELETE FROM sleep_summary
WHERE user_id = 'user123'
  AND query_date >= '2026-01-20'
  AND query_date <= '2026-02-08';

-- 生成 2026-01-20 00:00:00 至 2026-02-09 11:59:30，每30秒一条（MySQL 5.7 兼容）
-- sleep_status 覆盖 WAKE/DEEP/LIGHT/REM，且每晚都包含 WAKE→LIGHT/DEEP→REM→WAKE 完整周期
INSERT INTO health_data (user_id, heart_rate, breathing_rate, sleep_status, motion_index, upload_time)
SELECT
  'user123' AS user_id,
  CASE sleep_status
    WHEN 'DEEP' THEN FLOOR(50 + RAND() * 6)
    WHEN 'LIGHT' THEN FLOOR(56 + RAND() * 8)
    WHEN 'REM' THEN FLOOR(60 + RAND() * 10)
    ELSE FLOOR(70 + RAND() * 15)
  END AS heart_rate,
  CASE sleep_status
    WHEN 'DEEP' THEN FLOOR(10 + RAND() * 3)
    WHEN 'LIGHT' THEN FLOOR(12 + RAND() * 3)
    WHEN 'REM' THEN FLOOR(13 + RAND() * 4)
    ELSE FLOOR(15 + RAND() * 5)
  END AS breathing_rate,
  sleep_status,
  CASE sleep_status
    WHEN 'DEEP' THEN ROUND(5 + RAND() * 5, 2)
    WHEN 'LIGHT' THEN ROUND(10 + RAND() * 10, 2)
    WHEN 'REM' THEN ROUND(15 + RAND() * 15, 2)
    ELSE ROUND(30 + RAND() * 30, 2)
  END AS motion_index,
  upload_time
FROM (
  SELECT
    upload_time,
    CASE
      WHEN upload_time >= sleep_start AND upload_time < sleep_end THEN
        CASE
          WHEN ((UNIX_TIMESTAMP(upload_time) - UNIX_TIMESTAMP(sleep_start)) DIV 30) % 180 < 60 THEN 'LIGHT'
          WHEN ((UNIX_TIMESTAMP(upload_time) - UNIX_TIMESTAMP(sleep_start)) DIV 30) % 180 < 120 THEN 'DEEP'
          ELSE 'REM'
        END
      ELSE 'WAKE'
    END AS sleep_status
  FROM (
    SELECT
      upload_time,
      DATE_ADD(ref_date, INTERVAL (22 * 60 + start_offset) MINUTE) AS sleep_start,
      DATE_ADD(ref_date, INTERVAL (22 * 60 + start_offset + duration) MINUTE) AS sleep_end
    FROM (
      SELECT
        upload_time,
        ref_date,
        FLOOR(RAND(TO_DAYS(ref_date)) * 120) AS start_offset,
        420 + FLOOR(RAND(TO_DAYS(ref_date) + 1) * 60) AS duration
      FROM (
        SELECT
          TIMESTAMP('2026-01-20 00:00:00') + INTERVAL (n * 30) SECOND AS upload_time,
          DATE(IF(HOUR(TIMESTAMP('2026-01-20 00:00:00') + INTERVAL (n * 30) SECOND) < 12, 
                  DATE_SUB(TIMESTAMP('2026-01-20 00:00:00') + INTERVAL (n * 30) SECOND, INTERVAL 1 DAY), 
                  TIMESTAMP('2026-01-20 00:00:00') + INTERVAL (n * 30) SECOND)) AS ref_date
        FROM (
          SELECT (d4.i * 10000 + d3.i * 1000 + d2.i * 100 + d1.i * 10 + d0.i) AS n
          FROM (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d0
          CROSS JOIN
              (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d1
          CROSS JOIN
              (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d2
          CROSS JOIN
              (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d3
          CROSS JOIN
              (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d4
        ) nums
        WHERE n <= ((20 * 24 * 60 * 60 + 12 * 60 * 60) / 30) - 1 -- 覆盖到 2026-02-09 11:59:30
      ) base
    ) calc
  ) ranges
) s;
