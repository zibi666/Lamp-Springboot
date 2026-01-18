START TRANSACTION;

-- 2) 重新插入 1/1~1/16 每晚 30 秒一条（upload_time 精确到秒：...:00 / ...:30）
-- 注意：确保生成的睡眠数据包含完整的睡眠周期（WAKE -> NREM -> REM -> ... -> WAKE）
-- 且总时长 >= 30分钟，以满足后端生成报告的严格校验逻辑
INSERT INTO health_data (user_id, heart_rate, breathing_rate, sleep_status, motion_index, upload_time)
SELECT
  x.user_id,

  -- heart_rate（次/半分钟）
  CASE
    WHEN x.sleep_status = 'WAKE' THEN 78 + MOD(x.idx, 14)  -- 78~91
    WHEN x.sleep_status = 'NREM' THEN
      CASE WHEN x.profile IN ('MANY_AWAKE','MANY_GETUP') THEN 62 + MOD(x.idx, 10) ELSE 56 + MOD(x.idx, 8) END
    ELSE -- REM
      CASE WHEN x.profile = 'GOOD' THEN 64 + MOD(x.idx, 14) ELSE 68 + MOD(x.idx, 18) END
  END AS heart_rate,

  -- breathing_rate（次/半分钟）
  CASE
    WHEN x.sleep_status = 'WAKE' THEN 18 + MOD(x.idx, 4) -- 18~21
    WHEN x.sleep_status = 'NREM' THEN
      CASE WHEN x.profile IN ('MANY_AWAKE','MANY_GETUP') THEN 14 + MOD(x.idx, 5) ELSE 12 + MOD(x.idx, 4) END
    ELSE -- REM
      CASE WHEN x.profile = 'GOOD' THEN 14 + MOD(x.idx, 5) ELSE 15 + MOD(x.idx, 6) END
  END AS breathing_rate,

  x.sleep_status,

  -- motion_index：下床高 -> 离床0 -> 上床高 -> 重新入睡（REM/NREM）
  CASE
    WHEN x.sleep_status <> 'WAKE' THEN
      CASE
        WHEN x.sleep_status = 'NREM' THEN 3 + MOD(x.idx * 7, 18)     -- 3~20
        ELSE 7 + MOD(x.idx * 9, 24)                                  -- REM 7~30
      END
    ELSE
      CASE
        -- 起床次数多：事件窗口内按 tick120 走“下床高-离床0-上床高”
        WHEN x.profile = 'MANY_GETUP' AND x.event_wake_flag = 1 THEN
          CASE
            WHEN x.tick120 BETWEEN 0 AND 3   THEN 80 + MOD(x.idx, 15)  -- 下床动作（高）
            WHEN x.tick120 BETWEEN 4 AND 35  THEN 0                    -- 离床（0）
            WHEN x.tick120 BETWEEN 36 AND 39 THEN 75 + MOD(x.idx, 20)  -- 上床动作（高）
            ELSE 35 + MOD(x.idx * 11, 55)                               -- 醒着但在床上活动
          END

        -- 清醒次数多：更多尖峰但不一定离床
        WHEN x.profile = 'MANY_AWAKE' THEN
          (45 + MOD(x.idx * 13, 50)) + CASE WHEN MOD(x.idx, 55) BETWEEN 0 AND 3 THEN 20 ELSE 0 END

        -- 睡眠时间短：醒着时体动偏高
        WHEN x.profile = 'SHORT' THEN 40 + MOD(x.idx * 9, 45)

        -- 优质睡眠：醒着时体动较温和
        ELSE 30 + MOD(x.idx * 7, 35)
      END
  END AS motion_index,

  x.upload_time
FROM (
  -- 先算 final sleep_status（避免同层引用别名）
  SELECT
    s.user_id,
    s.profile,
    s.idx,
    s.upload_time,
    s.tick120,
    s.event_wake_flag,
    CASE WHEN s.event_wake_flag = 1 THEN 'WAKE' ELSE s.base_status END AS sleep_status
  FROM (
    -- 计算 base_status + event_wake_flag
    SELECT
      t.user_id,
      t.profile,
      t.idx,
      t.upload_time,
      t.tick120,
      t.minute_idx,
      t.mcycle,
      t.points,

      -- 基础睡眠状态（REM/NREM）+ 开头/结尾 WAKE
      -- 确保生成的周期满足 WAKE -> NREM -> REM -> ... -> WAKE
      CASE
        -- 开头必须是 WAKE (前4分钟)
        WHEN t.minute_idx < 8 THEN 'WAKE'
        
        -- 【关键修改】结尾前强制插入一段 REM (倒数 13~3分钟)，确保 REM -> WAKE
        -- (points/2) 是总分钟数。 结尾WAKE是最后3分钟。
        -- 这里我们在最后 WAKE 之前的 10 分钟强制设为 REM，从而满足 State 3 -> State 4 的变迁
        WHEN t.minute_idx >= (FLOOR(t.points / 2) - 13) AND t.minute_idx <= (FLOOR(t.points / 2) - 3) THEN 'REM'

        -- 结尾必须是 WAKE (最后3分钟)
        WHEN t.minute_idx > (FLOOR(t.points / 2) - 3) THEN 'WAKE'
        
        -- 中间部分按周期循环
        WHEN t.profile = 'GOOD' THEN
          CASE WHEN t.mcycle < 55 THEN 'NREM' WHEN t.mcycle < 80 THEN 'REM' ELSE 'NREM' END
        WHEN t.profile = 'SHORT' THEN
          CASE WHEN t.mcycle < 50 THEN 'NREM' WHEN t.mcycle < 75 THEN 'REM' ELSE 'NREM' END
        WHEN t.profile = 'MANY_AWAKE' THEN
          CASE WHEN t.mcycle < 45 THEN 'NREM' WHEN t.mcycle < 70 THEN 'REM' ELSE 'NREM' END
        ELSE -- MANY_GETUP
          CASE WHEN t.mcycle < 50 THEN 'NREM' WHEN t.mcycle < 75 THEN 'REM' ELSE 'NREM' END
      END AS base_status,

      -- 事件触发：清醒多 / 起床多（REM/NREM -> WAKE）
      CASE
        WHEN t.profile = 'MANY_AWAKE' AND (MOD(t.minute_idx, 55) BETWEEN 0 AND 4) THEN 1
        WHEN t.profile = 'MANY_GETUP' AND (MOD(t.minute_idx, 60) BETWEEN 0 AND 5) THEN 1
        ELSE 0
      END AS event_wake_flag

    FROM (
      -- 展开 1/1~1/16 每晚 30 秒点（upload_time 精确到秒）
      SELECT
        'user123' AS user_id,
        dp.profile,
        dp.points,
        n.n AS idx,
        DATE_ADD(dp.start_time, INTERVAL n.n * 30 SECOND) AS upload_time,
        FLOOR(n.n / 2) AS minute_idx,          -- 2条=1分钟
        MOD(FLOOR(n.n / 2), 90) AS mcycle,     -- 90分钟一个睡眠周期
        MOD(n.n, 120) AS tick120               -- 60分钟窗口（120*30s）
      FROM
        (
          -- 1/1~1/16：确保时长足够且覆盖完整周期
          -- SHORT 也要保证 > 30分钟 (60 points = 30 min)，这里设为 540 points = 4.5h 足够安全
          SELECT TIMESTAMP('2026-01-01 22:30:00') AS start_time, 1080 AS points, 'GOOD'       AS profile UNION ALL
          SELECT TIMESTAMP('2026-01-02 23:50:00'),               540,          'SHORT'                  UNION ALL
          SELECT TIMESTAMP('2026-01-03 22:40:00'),               900,          'MANY_AWAKE'             UNION ALL
          SELECT TIMESTAMP('2026-01-04 22:20:00'),               960,          'MANY_GETUP'             UNION ALL
          SELECT TIMESTAMP('2026-01-05 22:35:00'),               1020,         'GOOD'                   UNION ALL
          SELECT TIMESTAMP('2026-01-06 00:10:00'),               480,          'SHORT'                  UNION ALL
          SELECT TIMESTAMP('2026-01-07 22:55:00'),               930,          'MANY_AWAKE'             UNION ALL
          SELECT TIMESTAMP('2026-01-08 22:15:00'),               990,          'MANY_GETUP'             UNION ALL
          SELECT TIMESTAMP('2026-01-09 22:25:00'),               1080,         'GOOD'                   UNION ALL
          SELECT TIMESTAMP('2026-01-10 23:30:00'),               600,          'SHORT'                  UNION ALL
          SELECT TIMESTAMP('2026-01-11 22:45:00'),               870,          'MANY_AWAKE'             UNION ALL
          SELECT TIMESTAMP('2026-01-12 22:10:00'),               1020,         'MANY_GETUP'             UNION ALL
          SELECT TIMESTAMP('2026-01-13 22:30:00'),               1050,         'GOOD'                   UNION ALL
          SELECT TIMESTAMP('2026-01-14 00:05:00'),               510,          'SHORT'                  UNION ALL
          SELECT TIMESTAMP('2026-01-15 22:50:00'),               900,          'MANY_AWAKE'             UNION ALL
          SELECT TIMESTAMP('2026-01-16 22:05:00'),               1080,         'GOOD'
        ) dp
      JOIN
        (
          -- 0..9999 序列（每晚最多 1080 点，足够）
          SELECT (d3.i*1000 + d2.i*100 + d1.i*10 + d0.i) AS n
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
        ) n
        ON n.n BETWEEN 0 AND dp.points - 1
    ) t
  ) s
) x;

COMMIT;
