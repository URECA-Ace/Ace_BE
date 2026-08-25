-- KEYS[1]: 캠페인 메타데이터 Hash
-- KEYS[2]: 요청 상태 Hash
-- ARGV[1]: 사용자 식별자
-- ARGV[2]: Bitmap 세그먼트 내부 오프셋
-- ARGV[3]: requestId
-- ARGV[4]: Bitmap 세그먼트 식별자

-- MySQL 저장이 끝난 요청의 수명주기를 PENDING 에서 CONFIRMED 로 올린다.
-- 확정 수를 CAS 안에서 세지 않으면 재전달마다 카운터가 부풀어 발급 현황이 어긋난다.

local CONFIRMED_NOW = 0
local ALREADY_CONFIRMED = 1
local REQUEST_NOT_FOUND = 2
local NOT_CONFIRMABLE = 3
local CORRUPTED_STATE = 4
local INVALID_ARGUMENT = 5
local INTERNAL_WRITE_ERROR = 6

local DIAG_NONE = 0
local DIAG_REQUEST_READ = 401
local DIAG_REQUEST_WRITE = 411
local DIAG_COUNTER_INCREMENT = 412

local ACCEPTED = 0
local PENDING = 0
local CONFIRMED = 1
local SCHEMA_VERSION = 'v2'
local CONFIRMED_QUANTITY_FIELD = 'confirmedQuantity'

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

local function response(code, diagnosticStage, diagnosticMessage)
    return {code, diagnosticStage or DIAG_NONE, diagnosticMessage or ''}
end

local function errorDetail(result)
    if isError(result) then
        return string.sub(tostring(result['err']), 1, 256)
    end
    return string.sub('unexpected result: ' .. tostring(result), 1, 256)
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
    return response(INVALID_ARGUMENT)
end

-- 요청 부재와 이미 확정된 재시도 우선 처리
local requestState = redis.pcall('HMGET', KEYS[2], requestId, '__schema__')
if isError(requestState) then
    return response(CORRUPTED_STATE, DIAG_REQUEST_READ, errorDetail(requestState))
end

local previous = requestState[1]
if not previous then
    return response(REQUEST_NOT_FOUND)
end
if requestState[2] ~= SCHEMA_VERSION then
    return response(CORRUPTED_STATE)
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
    return response(CORRUPTED_STATE)
end

-- 다른 사용자 또는 Bitmap 위치의 확정 차단
if previousUserId ~= userId or previousSegmentId ~= bitmapSegmentId
        or numericOffset ~= bitOffset then
    return response(INVALID_ARGUMENT)
end

-- 재전달로 다시 들어온 확정은 카운터를 올리지 않고 성공으로 되돌린다
if numericCode == ACCEPTED and numericLifecycle == CONFIRMED then
    return response(ALREADY_CONFIRMED)
end
if numericCode ~= ACCEPTED or numericLifecycle ~= PENDING then
    return response(NOT_CONFIRMABLE)
end

-- 실제 확정 대상 한정 핵심 키 타입 검증
if keyType(KEYS[1]) ~= 'hash' or keyType(KEYS[2]) ~= 'hash' then
    return response(CORRUPTED_STATE)
end

local metadata = redis.call('HMGET', KEYS[1], 'totalStock', 'schemaVersion')
local totalStock = tonumber(metadata[1])
if metadata[2] ~= SCHEMA_VERSION or not isInteger(totalStock) or totalStock <= 0 then
    return response(CORRUPTED_STATE)
end

local redisTime = redis.call('TIME')
local now = (tonumber(redisTime[1]) * 1000)
        + math.floor(tonumber(redisTime[2]) / 1000)

-- 판정 결과는 그대로 두고 수명주기와 갱신 시각만 올린다
local confirmedRecord = table.concat({
    SCHEMA_VERSION,
    userId,
    bitmapSegmentId,
    tostring(bitOffset),
    tostring(ACCEPTED),
    tostring(CONFIRMED),
    tostring(sequence),
    tostring(remainingAtDecision),
    tostring(decidedAt),
    tostring(now)
}, '|')

-- 상태 변경 선행과 역순 롤백 기반 확정 상태 갱신
local result = redis.pcall('HSET', KEYS[2], requestId, confirmedRecord)
if isError(result) or result ~= 0 then
    return response(INTERNAL_WRITE_ERROR, DIAG_REQUEST_WRITE, errorDetail(result))
end

-- 필드가 없으면 HINCRBY 가 0에서 시작하므로 초기화 스크립트를 고치지 않아도 된다
result = redis.pcall('HINCRBY', KEYS[1], CONFIRMED_QUANTITY_FIELD, 1)
if isError(result) or not isInteger(result) or result <= 0 or result > totalStock then
    redis.call('HSET', KEYS[2], requestId, previous)
    if not isError(result) then
        redis.call('HINCRBY', KEYS[1], CONFIRMED_QUANTITY_FIELD, -1)
    end
    return response(INTERNAL_WRITE_ERROR, DIAG_COUNTER_INCREMENT, errorDetail(result))
end

return response(CONFIRMED_NOW)
