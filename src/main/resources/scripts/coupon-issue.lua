-- KEYS[1]: 캠페인 메타데이터 Hash
-- KEYS[2]: 잔여 재고 String
-- KEYS[3]: 발급 순번 String
-- KEYS[4]: 사용자 발급 Bitmap 세그먼트
-- KEYS[5]: 요청 상태 Hash
-- KEYS[6]: 비동기 저장 대기 Stream
-- ARGV[1]: 사용자 식별자
-- ARGV[2]: Bitmap 세그먼트 내부 오프셋
-- ARGV[3]: requestId
-- ARGV[4]: 캠페인 식별자
-- ARGV[5]: Bitmap 세그먼트 식별자

local ACCEPTED = 0
local SOLD_OUT = 1
local ALREADY_ISSUED = 2
local EVENT_NOT_OPEN = 3
local EVENT_CLOSED = 4
local CAMPAIGN_NOT_INITIALIZED = 5
local IDEMPOTENCY_CONFLICT = 6
local CORRUPTED_STATE = 7
local PERSISTENCE_FAILED = 8
local INVALID_ARGUMENT = 9
local INTERNAL_WRITE_ERROR = 10

local PENDING = 0
local CONFIRMED = 1
local COMPENSATED = 2
local TERMINAL = 3
local SCHEMA_VERSION = 'v2'

local userId = ARGV[1]
local bitOffset = tonumber(ARGV[2])
local requestId = ARGV[3]
local campaignId = ARGV[4]
local bitmapSegmentId = ARGV[5]

local function response(code, sequence, remaining, decidedAt)
    return {code, sequence or -1, remaining or -1, decidedAt or 0}
end

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

local function parseRecord(value)
    return string.match(value,
            '^([^|]+)|([^|]+)|([^|]+)|([^|]+)|([^|]+)|'
            .. '([^|]+)|([^|]+)|([^|]+)|([^|]+)|([^|]+)$')
end

local function packRecord(decisionCode, lifecycle, sequence, remaining, decidedAt, updatedAt)
    return table.concat({
        SCHEMA_VERSION,
        userId,
        bitmapSegmentId,
        tostring(bitOffset),
        tostring(decisionCode),
        tostring(lifecycle),
        tostring(sequence or -1),
        tostring(remaining or -1),
        tostring(decidedAt),
        tostring(updatedAt)
    }, '|')
end

local function remember(decisionCode, lifecycle, sequence, remaining, decidedAt)
    local result = redis.pcall('HSET', KEYS[5], requestId,
            packRecord(decisionCode, lifecycle, sequence, remaining, decidedAt, decidedAt))
    return not isError(result) and result == 1
end

local function validStoredState(code, lifecycle)
    if code == ACCEPTED then
        return lifecycle == PENDING or lifecycle == CONFIRMED
    end
    if code == PERSISTENCE_FAILED then
        return lifecycle == COMPENSATED
    end
    return code >= SOLD_OUT and code <= EVENT_CLOSED and lifecycle == TERMINAL
end

-- 외부 식별자와 Bitmap 범위 입력 검증
if not validCanonicalNumber(userId, 19, false)
        or not validToken(requestId, 128)
        or not validCanonicalNumber(campaignId, 19, false)
        or not validCanonicalNumber(bitmapSegmentId, 16, true)
        or not isInteger(bitOffset) or bitOffset < 0 then
    return response(INVALID_ARGUMENT, -1, -1, 0)
end

-- requestId 기반 멱등 재요청 우선 처리
local requestState = redis.pcall('HMGET', KEYS[5], requestId, '__schema__')
if isError(requestState) then
    return response(CORRUPTED_STATE, -1, -1, 0)
end

local previous = requestState[1]
local requestSchema = requestState[2]
if previous then
    local version, previousUserId, previousSegmentId, previousOffset,
            previousCode, previousLifecycle, previousSequence,
            previousRemaining, previousDecidedAt, previousUpdatedAt = parseRecord(previous)
    local numericOffset = tonumber(previousOffset)
    local numericCode = tonumber(previousCode)
    local numericLifecycle = tonumber(previousLifecycle)
    local numericSequence = tonumber(previousSequence)
    local numericRemaining = tonumber(previousRemaining)
    local numericDecidedAt = tonumber(previousDecidedAt)
    local numericUpdatedAt = tonumber(previousUpdatedAt)

    if requestSchema ~= SCHEMA_VERSION or version ~= SCHEMA_VERSION
            or not isInteger(numericOffset)
            or not isInteger(numericCode) or not isInteger(numericLifecycle)
            or not validStoredState(numericCode, numericLifecycle)
            or not isInteger(numericSequence) or not isInteger(numericRemaining)
            or not isInteger(numericDecidedAt) or numericDecidedAt <= 0
            or not isInteger(numericUpdatedAt) or numericUpdatedAt < numericDecidedAt then
        return response(CORRUPTED_STATE, -1, -1, 0)
    end

    -- 동일 requestId의 논리 입력 불변성 검증
    if previousUserId ~= userId or previousSegmentId ~= bitmapSegmentId
            or numericOffset ~= bitOffset then
        return response(IDEMPOTENCY_CONFLICT, -1, -1, numericDecidedAt)
    end

    return response(numericCode, numericSequence, numericRemaining, numericDecidedAt)
end

-- 핵심 상태 단일 읽기 기반 스키마와 숫자 검증
local metadata = redis.pcall('HMGET', KEYS[1],
        'totalStock', 'openAt', 'closeAt', 'expireAt',
        'bitmapSegmentBits', 'schemaVersion')
local stockValue = redis.pcall('GET', KEYS[2])
local sequenceValue = redis.pcall('GET', KEYS[3])

if isError(metadata) or isError(stockValue) or isError(sequenceValue) then
    return response(CORRUPTED_STATE, -1, -1, 0)
end

if not metadata[1] and not metadata[2] and not metadata[3]
        and not metadata[4] and not metadata[5] and not metadata[6] then
    return response(CAMPAIGN_NOT_INITIALIZED, -1, -1, 0)
end

local totalStock = tonumber(metadata[1])
local openAt = tonumber(metadata[2])
local closeAt = tonumber(metadata[3])
local expireAt = tonumber(metadata[4])
local bitmapSegmentBits = tonumber(metadata[5])
local schemaVersion = metadata[6]
local stock = tonumber(stockValue)
local currentSequence = tonumber(sequenceValue)
local redisTime = redis.call('TIME')
local now = (tonumber(redisTime[1]) * 1000)
        + math.floor(tonumber(redisTime[2]) / 1000)

if schemaVersion ~= SCHEMA_VERSION or requestSchema ~= SCHEMA_VERSION
        or not isInteger(totalStock) or totalStock <= 0
        or not isInteger(openAt) or not isInteger(closeAt) or not isInteger(expireAt)
        or openAt >= closeAt or closeAt >= expireAt
        or not isInteger(bitmapSegmentBits) or bitmapSegmentBits <= 0
        or bitOffset >= bitmapSegmentBits
        or not isInteger(stock) or stock < 0 or stock > totalStock
        or not isInteger(currentSequence) or currentSequence < 0 then
    return response(CORRUPTED_STATE, -1, -1, now)
end

-- Redis 서버 시각 기반 캠페인 구간 판정
if now < openAt then
    if not remember(EVENT_NOT_OPEN, TERMINAL, -1, stock, now) then
        return response(INTERNAL_WRITE_ERROR, -1, -1, now)
    end
    return response(EVENT_NOT_OPEN, -1, stock, now)
end

if now >= closeAt then
    if not remember(EVENT_CLOSED, TERMINAL, -1, stock, now) then
        return response(INTERNAL_WRITE_ERROR, -1, -1, now)
    end
    return response(EVENT_CLOSED, -1, stock, now)
end

-- 저장 대기 상태 포함 사용자 중복 차단
local issued = redis.pcall('GETBIT', KEYS[4], bitOffset)
if isError(issued) then
    return response(CORRUPTED_STATE, -1, -1, now)
end

if issued == 1 then
    if not remember(ALREADY_ISSUED, TERMINAL, -1, stock, now) then
        return response(INTERNAL_WRITE_ERROR, -1, -1, now)
    end
    return response(ALREADY_ISSUED, -1, stock, now)
end

-- 0 재고 선확인 기반 음수 재고 차단
if stock == 0 then
    if not remember(SOLD_OUT, TERMINAL, -1, stock, now) then
        return response(INTERNAL_WRITE_ERROR, -1, -1, now)
    end
    return response(SOLD_OUT, -1, stock, now)
end

-- 승인 경로 한정 Stream 타입 검증
local streamTypeResult = redis.call('TYPE', KEYS[6])
local streamType = streamTypeResult['ok'] or streamTypeResult
if streamType ~= 'none' and streamType ~= 'stream' then
    return response(CORRUPTED_STATE, -1, -1, now)
end

local bitmapWasMissing = redis.call('EXISTS', KEYS[4]) == 0
local streamWasMissing = streamType == 'none'
local remaining = stock - 1
local sequence = currentSequence + 1
local acceptedRecord = packRecord(ACCEPTED, PENDING, sequence, remaining, now, now)
local streamEntryId = nil
local bitmapChanged = false
local stockChanged = false
local sequenceChanged = false

-- 승인 경로 부분 쓰기의 역순 원복 처리
local function rollbackAccepted()
    if sequenceChanged then redis.call('DECR', KEYS[3]) end
    if stockChanged then redis.call('INCR', KEYS[2]) end
    if bitmapChanged then
        if bitmapWasMissing then redis.call('DEL', KEYS[4])
        else redis.call('SETBIT', KEYS[4], bitOffset, 0) end
    end
    if streamEntryId then
        if streamWasMissing then redis.call('DEL', KEYS[6])
        else redis.call('XDEL', KEYS[6], streamEntryId) end
    end
    redis.call('HDEL', KEYS[5], requestId)
end

-- 가변 메모리 쓰기 선행과 역순 롤백 기반 승인 상태 갱신
local result = redis.pcall('HSET', KEYS[5], requestId, acceptedRecord)
if isError(result) or result ~= 1 then
    return response(INTERNAL_WRITE_ERROR, -1, -1, now)
end

result = redis.pcall('XADD', KEYS[6], '*',
        'type', 'ISSUE',
        'requestId', requestId,
        'campaignId', campaignId,
        'userId', userId,
        'bitmapSegmentId', bitmapSegmentId,
        'bitOffset', tostring(bitOffset),
        'issueSequence', tostring(sequence),
        'decidedAt', tostring(now))
if isError(result) then
    rollbackAccepted()
    return response(INTERNAL_WRITE_ERROR, -1, -1, now)
end
streamEntryId = result

result = redis.pcall('SETBIT', KEYS[4], bitOffset, 1)
if isError(result) or result ~= 0 then
    rollbackAccepted()
    return response(INTERNAL_WRITE_ERROR, -1, -1, now)
end
bitmapChanged = true

-- 동적 생성 키의 캠페인 공통 만료 갱신
if bitmapWasMissing then
    result = redis.pcall('PEXPIREAT', KEYS[4], expireAt)
    if isError(result) or result ~= 1 then
        rollbackAccepted()
        return response(INTERNAL_WRITE_ERROR, -1, -1, now)
    end
end

if streamWasMissing then
    result = redis.pcall('PEXPIREAT', KEYS[6], expireAt)
    if isError(result) or result ~= 1 then
        rollbackAccepted()
        return response(INTERNAL_WRITE_ERROR, -1, -1, now)
    end
end

-- 단일 Lua 임계 구역 내 재고 차감과 순번 갱신
result = redis.pcall('DECR', KEYS[2])
if not isError(result) then stockChanged = true end
if isError(result) or result ~= remaining then
    rollbackAccepted()
    return response(INTERNAL_WRITE_ERROR, -1, -1, now)
end

result = redis.pcall('INCR', KEYS[3])
if not isError(result) then sequenceChanged = true end
if isError(result) or result ~= sequence then
    rollbackAccepted()
    return response(INTERNAL_WRITE_ERROR, -1, -1, now)
end

return response(ACCEPTED, sequence, remaining, now)
