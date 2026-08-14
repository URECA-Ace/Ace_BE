-- KEYS[1]: 캠페인 메타데이터 Hash
-- KEYS[2]: 잔여 재고 String
-- KEYS[3]: 사용자 발급 Bitmap 세그먼트
-- KEYS[4]: 요청 상태 Hash
-- KEYS[5]: 비동기 저장 대기 Stream
-- ARGV[1]: 사용자 식별자
-- ARGV[2]: Bitmap 오프셋
-- ARGV[3]: requestId

local COMPENSATED = 0
local REQUEST_NOT_FOUND = 1
local NOT_COMPENSABLE = 2
local CORRUPTED_STATE = 3

local function keyType(key)
    local result = redis.call('TYPE', key)
    return result['ok'] or result
end

-- 보상 후속 쓰기 오류 방지용 키 타입 선검증
if keyType(KEYS[1]) ~= 'hash' or keyType(KEYS[2]) ~= 'string'
        or keyType(KEYS[3]) ~= 'string' or keyType(KEYS[4]) ~= 'hash'
        or keyType(KEYS[5]) ~= 'stream' then
    return CORRUPTED_STATE
end

local previous = redis.call('HGET', KEYS[4], ARGV[3])
if not previous then
    return REQUEST_NOT_FOUND
end

local userId, code, status, sequence, _, decidedAt =
        string.match(previous, '^([^|]+)|([^|]+)|([^|]+)|([^|]+)|([^|]+)|([^|]+)|')
if not userId or userId ~= ARGV[1] or not code or not status or not sequence or not decidedAt then
    return CORRUPTED_STATE
end

-- 확정 전 상태만 허용하는 단일 보상
if code ~= '0' or (status ~= 'ACCEPTED' and status ~= 'PROCESSING' and status ~= 'FAILED') then
    return NOT_COMPENSABLE
end

local bitOffset = tonumber(ARGV[2])
local stock = tonumber(redis.call('GET', KEYS[2]))
local totalStock = tonumber(redis.call('HGET', KEYS[1], 'totalStock'))
if not bitOffset or not stock or not totalStock
        or redis.call('GETBIT', KEYS[3], bitOffset) ~= 1 or stock >= totalStock then
    return CORRUPTED_STATE
end

local restoredStock = redis.call('INCR', KEYS[2])
redis.call('SETBIT', KEYS[3], bitOffset, 0)
local redisTime = redis.call('TIME')
local now = (tonumber(redisTime[1]) * 1000) + math.floor(tonumber(redisTime[2]) / 1000)
local packed = table.concat({userId, '8', 'COMPENSATED', sequence,
        tostring(restoredStock), decidedAt, '-'}, '|')
redis.call('HSET', KEYS[4], ARGV[3], packed)
redis.call('XADD', KEYS[5], '*',
        'type', 'COMPENSATE',
        'requestId', ARGV[3],
        'userId', userId,
        'issueSequence', sequence,
        'compensatedAt', tostring(now))

return COMPENSATED
