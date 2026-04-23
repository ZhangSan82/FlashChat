-- Merge message persist (XADD to Stream) and room sliding-window update
-- (ZADD + optional trim + optional expire) into a single Redis round-trip.
--
-- Deployment note: this script touches two keys (stream + window). It is safe
-- on single-instance Redis (the current deployment). Before adopting Redis
-- Cluster, the two keys must hash to the same slot (or be split back to
-- separate calls) — cross-slot EVAL is rejected by cluster nodes.
--
-- KEYS[1] = persist stream key           (flashchat:msg:persist:stream)
-- KEYS[2] = room window key              (flashchat:msg:window:{roomId})
--
-- ARGV[1] = stream field name            ("payload")
-- ARGV[2] = stream payload (JSON of MessageDO)
-- ARGV[3] = stream MAXLEN (approximate)
-- ARGV[4] = window score (message dbId, integer as string)
-- ARGV[5] = window member JSON (ChatBroadcastMsgRespDTO)
-- ARGV[6] = doTrim flag: 1 or 0
-- ARGV[7] = doExpire flag: 1 or 0
-- ARGV[8] = windowSize
-- ARGV[9] = ttlSeconds

local streamKey    = KEYS[1]
local windowKey    = KEYS[2]

local fieldName    = ARGV[1]
local payload      = ARGV[2]
local streamMaxLen = tonumber(ARGV[3])

local score        = ARGV[4]
local member       = ARGV[5]
local doTrim       = ARGV[6]
local doExpire     = ARGV[7]
local windowSize   = tonumber(ARGV[8])
local ttlSeconds   = tonumber(ARGV[9])

redis.call('XADD', streamKey, 'MAXLEN', '~', streamMaxLen, '*', fieldName, payload)

redis.call('ZADD', windowKey, score, member)

if doTrim == '1' then
    redis.call('ZREMRANGEBYRANK', windowKey, 0, -(windowSize + 1))
end

if doExpire == '1' then
    redis.call('EXPIRE', windowKey, ttlSeconds)
end

return 1
