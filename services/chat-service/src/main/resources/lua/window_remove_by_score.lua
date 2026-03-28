-- 按 score 移除 Sorted Set 中的 member（原子操作）
--
-- 用途：房主删除消息时从窗口中移除，不保留占位
--
-- KEYS[1] = 窗口 key
-- ARGV[1] = score（消息的 dbId）
--
-- 返回：1=成功移除  0=窗口不存在或该 score 不在窗口中

local key = KEYS[1]
local score = ARGV[1]

if redis.call('EXISTS', key) == 0 then
    return 0
end

local old = redis.call('ZRANGEBYSCORE', key, score, score)
if #old > 0 then
    redis.call('ZREM', key, old[1])
    return 1
end

return 0