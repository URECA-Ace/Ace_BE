-- KEYS[1]: 캠페인 메타데이터 Hash
-- KEYS[2]: 잔여 재고 String
-- KEYS[3]: 사용자 발급 Bitmap 세그먼트
-- KEYS[4]: 요청 상태 Hash
-- KEYS[5]: 비동기 저장 대기 Stream
-- ARGV[1]: 사용자 식별자
-- ARGV[2]: Bitmap 세그먼트 내부 오프셋
-- ARGV[3]: requestId
-- ARGV[4]: Bitmap 세그먼트 식별자

local COMPENSATED_NOW = 0
local ALREADY_COMPENSATED = 1
local REQUEST_NOT_FOUND = 2
local NOT_COMPENSABLE = 3
local CORRUPTED_STATE = 4
local INVALID_ARGUMENT = 5
local INTERNAL_WRITE_ERROR = 6

local ACCEPTED = 0
local PERSISTENCE_FAILED = 8
local PENDING = 0
local COMPENSATED = 2
local SCHEMA_VERSION = 'v2'

local userId = ARGV[1]
local bitOffset = tonumber(ARGV[2])
local requestId = ARGV[3]
local bitmapSegmentId = ARGV[4]

local function isInteger(value)
    return value and value % 1 == 0
end

local function isError(result)
    return type(result) == 'table' and result['err'] ~= nil
end

local function validToken(value, maxLength)
    return value and #value > 0 and #value <= maxLength
            and not string.find(value, '|', 1, true)
end

local function validCanonicalNumber(value, maxLength, allowZero)
    if not validToken(value, maxLength)
            or string.match(value, '^%d+$') == nil
            or (#value > 1 and string.sub(value, 1, 1) == '0') then
        return false
    end
    return allowZero or value ~= '0'
end

local function keyType(key)
    local result = redis.call('TYPE', key)
    return result['ok'] or result
end

local function parseRecord(value)
    return string.match(value,
            '^([^|]+)|([^|]+)|([^|]+)|([^|]+)|([^|]+)|'
            .. '([^|]+)|([^|]+)|([^|]+)|([^|]+)|([^|]+)$')
end

-- 외부 식별자와 Bitmap 위치 입력 검증
if not validCanonicalNumber(userId, 19, false)
        or not validToken(requestId, 128)
        or not validCanonicalNumber(bitmapSegmentId, 16, true)
        or not isInteger(bitOffset) or bitOffset < 0 then
    return INVALID_ARGUMENT
end

-- 요청 부재와 이미 보상된 재시도 우선 처리
local requestState = redis.pcall('HMGET', KEYS[4], requestId, '__schema__')
if isError(requestState) then
    return CORRUPTED_STATE
end

local previous = requestState[1]
if not previous then
    return REQUEST_NOT_FOUND
end
if requestState[2] ~= SCHEMA_VERSION then
    return CORRUPTED_STATE
end

local version, previousUserId, previousSegmentId, previousOffset,
        previousCode, previousLifecycle, previousSequence,
        previousRemaining, previousDecidedAt, previousUpdatedAt = parseRecord(previous)
local numericOffset = tonumber(previousOffset)
local numericCode = tonumber(previousCode)
local numericLifecycle = tonumber(previousLifecycle)
local sequence = tonumber(previousSequence)
local remainingAtDecision = tonumber(previousRemaining)
local decidedAt = tonumber(previousDecidedAt)
local updatedAt = tonumber(previousUpdatedAt)

if version ~= SCHEMA_VERSION or not isInteger(numericOffset)
        or not isInteger(numericCode) or not isInteger(numericLifecycle)
        or not isInteger(sequence) or not isInteger(remainingAtDecision)
        or not isInteger(decidedAt) or decidedAt <= 0
        or not isInteger(updatedAt) or updatedAt < decidedAt then
    return CORRUPTED_STATE
end

-- 다른 사용자 또는 Bitmap 위치의 보상 차단
if previousUserId ~= userId or previousSegmentId ~= bitmapSegmentId
        or numericOffset ~= bitOffset then
    return INVALID_ARGUMENT
end

if numericCode == PERSISTENCE_FAILED and numericLifecycle == COMPENSATED then
    return ALREADY_COMPENSATED
end
if numericCode ~= ACCEPTED or numericLifecycle ~= PENDING then
    return NOT_COMPENSABLE
end

-- 실제 보상 대상 한정 핵심 키와 Stream 타입 검증
if keyType(KEYS[1]) ~= 'hash' or keyType(KEYS[2]) ~= 'string'
        or keyType(KEYS[3]) ~= 'string' or keyType(KEYS[4]) ~= 'hash'
        or keyType(KEYS[5]) ~= 'stream' then
    return CORRUPTED_STATE
end

local metadata = redis.call('HMGET', KEYS[1],
        'totalStock', 'bitmapSegmentBits', 'schemaVersion')
local totalStock = tonumber(metadata[1])
local bitmapSegmentBits = tonumber(metadata[2])
local stock = tonumber(redis.call('GET', KEYS[2]))

if metadata[3] ~= SCHEMA_VERSION
        or not isInteger(totalStock) or totalStock <= 0
        or not isInteger(bitmapSegmentBits) or bitOffset >= bitmapSegmentBits
        or not isInteger(stock) or stock < 0 or stock >= totalStock
        or redis.call('GETBIT', KEYS[3], bitOffset) ~= 1 then
    return CORRUPTED_STATE
end

local redisTime = redis.call('TIME')
local now = (tonumber(redisTime[1]) * 1000)
        + math.floor(tonumber(redisTime[2]) / 1000)
local restoredStock = stock + 1
local compensatedRecord = table.concat({
    SCHEMA_VERSION,
    userId,
    bitmapSegmentId,
    tostring(bitOffset),
    tostring(PERSISTENCE_FAILED),
    tostring(COMPENSATED),
    tostring(sequence),
    tostring(restoredStock),
    tostring(decidedAt),
    tostring(now)
}, '|')
local compensationEntryId = nil
local bitmapChanged = false
local stockChanged = false

-- 보상 경로 부분 쓰기의 역순 원복 처리
local function rollbackCompensation()
    if stockChanged then redis.call('DECR', KEYS[2]) end
    if bitmapChanged then redis.call('SETBIT', KEYS[3], bitOffset, 1) end
    if compensationEntryId then redis.call('XDEL', KEYS[5], compensationEntryId) end
    redis.call('HSET', KEYS[4], requestId, previous)
end

-- 상태 변경 선행과 역순 롤백 기반 보상 상태 갱신
local result = redis.pcall('HSET', KEYS[4], requestId, compensatedRecord)
if isError(result) or result ~= 0 then
    return INTERNAL_WRITE_ERROR
end

result = redis.pcall('XADD', KEYS[5], '*',
        'type', 'COMPENSATE',
        'requestId', requestId,
        'userId', userId,
        'bitmapSegmentId', bitmapSegmentId,
        'bitOffset', tostring(bitOffset),
        'issueSequence', tostring(sequence),
        'compensatedAt', tostring(now))
if isError(result) then
    rollbackCompensation()
    return INTERNAL_WRITE_ERROR
end
compensationEntryId = result

result = redis.pcall('SETBIT', KEYS[3], bitOffset, 0)
if isError(result) or result ~= 1 then
    rollbackCompensation()
    return INTERNAL_WRITE_ERROR
end
bitmapChanged = true

-- 재고 상한 검증 후 단일 수량 원복 갱신
result = redis.pcall('INCR', KEYS[2])
if not isError(result) then stockChanged = true end
if isError(result) or result ~= restoredStock then
    rollbackCompensation()
    return INTERNAL_WRITE_ERROR
end

return COMPENSATED_NOW
