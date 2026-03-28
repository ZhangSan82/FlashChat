-- 按 score 替换 Sorted Set 中的 member（原子操作）
--
-- 用途：消息撤回时替换窗口中的消息 JSON、emoji reaction 更新
--
-- KEYS[1] = 窗口 key（如 flashchat:msg:window:{roomId}）
-- ARGV[1] = score（消息的 dbId）
-- ARGV[2] = 新的 member JSON
--
-- 返回：1=成功  0=窗口不存在（跳过）
--
-- 为什么需要 Lua：
--   ZREM 需要知道 member 的完整值（JSON 字符串），不能按 score 直接删除。
--   必须先 ZRANGEBYSCORE 拿到旧值再 ZREM，这两步之间如果有并发写入
--   触发 ZREMRANGEBYRANK 窗口裁剪，可能导致数据不一致。
--   Lua 在 Redis 单线程中原子执行，避免中间状态。

local key = KEYS[1]
local score = ARGV[1]
local newMember = ARGV[2]

if redis.call('EXISTS', key) == 0 then
    return 0
end

local old = redis.call('ZRANGEBYSCORE', key, score, score)
if #old > 0 then
    redis.call('ZREM', key, old[1])
end

redis.call('ZADD', key, score, newMember)
return 1