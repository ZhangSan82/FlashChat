-- 多维度滑动窗口限流（一次网络往返完成所有检查）
--
-- 两阶段执行：
--   Phase 1: 逐维度清理过期条目 + 计数，任一超限立即返回（不写入新条目）
--   Phase 2: 全部通过后，写入所有维度
--
-- 为什么分两阶段：
--   如果边检查边写入，维度1通过并ZADD后维度2失败，维度1多计了一次请求
--   两阶段保证：要么全部维度都记录这次请求，要么都不记录
--
-- KEYS[1..N]  = 限流 key（1-3 个）
-- ARGV[1]     = 当前时间戳（毫秒）
-- ARGV[2]     = 本次请求的唯一标识（Java 侧传入 nanoTime，保证 member 唯一性）
-- ARGV[3]     = 维度数量（1-3）
-- 每个维度 i（从 1 开始）占 2 个参数：
--   ARGV[4 + (i-1)*2] = 窗口大小（毫秒）
--   ARGV[5 + (i-1)*2] = 窗口内最大请求数
--
-- 返回值：
--   0 = 全部通过
--   i = 第 i 个维度超限（1=用户全局 2=用户+房间 3=房间全局）

local now = tonumber(ARGV[1])
local nonce = ARGV[2]
local dimensions = tonumber(ARGV[3])

-- ===== Phase 1: 检查所有维度（只读 + 清理过期） =====
for i = 1, dimensions do
    local key = KEYS[i]
    local paramBase = 4 + (i - 1) * 2
    local window = tonumber(ARGV[paramBase])
    local maxCount = tonumber(ARGV[paramBase + 1])

    -- 清理窗口外的过期条目（这是幂等的清理操作，不影响正确性）
    redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

    -- 计数当前窗口内的请求数
    local count = redis.call('ZCARD', key)

    if count >= maxCount then
        -- 超限：立即返回，不写入任何维度
        return i
    end
end

-- ===== Phase 2: 全部通过，写入所有维度 =====
for i = 1, dimensions do
    local key = KEYS[i]
    local paramBase = 4 + (i - 1) * 2
    local window = tonumber(ARGV[paramBase])

    -- member 使用 nonce:维度序号，保证全局唯一
    redis.call('ZADD', key, now, nonce .. ':' .. i)

    -- 设置 key 过期时间 = 窗口大小，自动回收不再活跃的 key
    redis.call('PEXPIRE', key, window)
end

return 0