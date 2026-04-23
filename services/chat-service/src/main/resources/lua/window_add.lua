-- Append one message into the room window, and optionally trim / refresh TTL.
--
-- KEYS[1] = room window key, e.g. flashchat:msg:window:{roomId}
--
-- ARGV[1] = score (message dbId)
-- ARGV[2] = member JSON
-- ARGV[3] = doTrim flag: 1 or 0
-- ARGV[4] = doExpire flag: 1 or 0
-- ARGV[5] = windowSize
-- ARGV[6] = ttlSeconds
--
-- Java side still decides whether this write should trim / refresh TTL.
-- Lua only makes the Redis-side execution atomic and avoids dispatching
-- multiple commands on the hot path.

local key = KEYS[1]
local score = ARGV[1]
local member = ARGV[2]
local doTrim = ARGV[3]
local doExpire = ARGV[4]
local windowSize = tonumber(ARGV[5])
local ttlSeconds = tonumber(ARGV[6])

redis.call('ZADD', key, score, member)

if doTrim == '1' then
    redis.call('ZREMRANGEBYRANK', key, 0, -(windowSize + 1))
end

if doExpire == '1' then
    redis.call('EXPIRE', key, ttlSeconds)
end

return 1
