-- KEYS[1]: 캠페인 메타데이터 Hash
-- KEYS[2]: 잔여 재고 String
-- KEYS[3]: 발급 순번 String
-- KEYS[4]: 사용자 발급 Bitmap 세그먼트
-- KEYS[5]: 요청 상태 Hash
-- KEYS[6]: 비동기 저장 대기 Stream
-- ARGV[1]: 사용자 식별자
-- ARGV[2]: Bitmap 오프셋
-- ARGV[3]: requestId
-- ARGV[4]: 캠페인 식별자

local ACCEPTED = 0
local SOLD_OUT = 1
local ALREADY_ISSUED = 2
local EVENT_NOT_OPEN = 3
local EVENT_CLOSED = 4
local CAMPAIGN_NOT_INITIALIZED = 5
local IDEMPOTENCY_CONFLICT = 6
local CORRUPTED_STATE = 7

local userId = ARGV[1]
local bitOffset = tonumber(ARGV[2])
local requestId = ARGV[3]
local campaignId = ARGV[4]

local function keyType(key)
    local result = redis.call('TYPE', key)
    return result['ok'] or result
end

local function response(code, sequence, remaining, decidedAt)
    return {code, sequence or -1, remaining or -1, decidedAt}
end

local function requestStatus(code)
    if code == ACCEPTED then return 'ACCEPTED' end
    if code == SOLD_OUT then return 'REJECTED_SOLD_OUT' end
    if code == ALREADY_ISSUED then return 'REJECTED_DUPLICATE' end
    if code == EVENT_NOT_OPEN then return 'REJECTED_NOT_OPEN' end
    if code == EVENT_CLOSED then return 'REJECTED_CLOSED' end
    return 'UNKNOWN'
end

local function remember(code, sequence, remaining, decidedAt, streamId)
    local packed = table.concat({
        userId,
        tostring(code),
        requestStatus(code),
        tostring(sequence or -1),
        tostring(remaining or -1),
        tostring(decidedAt),
        streamId or '-'
    }, '|')
    redis.call('HSET', KEYS[5], requestId, packed)
end

-- 초기화 누락과 키 타입 훼손의 쓰기 전 차단
local metadataType = keyType(KEYS[1])
if metadataType ~= 'hash' then
    if metadataType == 'none' then
        return response(CAMPAIGN_NOT_INITIALIZED, -1, -1, 0)
    end
    return response(CORRUPTED_STATE, -1, -1, 0)
end

if keyType(KEYS[2]) ~= 'string' or keyType(KEYS[3]) ~= 'string'
        or keyType(KEYS[5]) ~= 'hash' then
    return response(CORRUPTED_STATE, -1, -1, 0)
end

local bitmapType = keyType(KEYS[4])
local streamType = keyType(KEYS[6])
if (bitmapType ~= 'none' and bitmapType ~= 'string')
        or (streamType ~= 'none' and streamType ~= 'stream') then
    return response(CORRUPTED_STATE, -1, -1, 0)
end

-- 멱등 재요청의 최초 판정 결과 반환
local previous = redis.call('HGET', KEYS[5], requestId)
if previous then
    local previousUser, previousCode, _, previousSequence, previousRemaining, previousDecidedAt =
            string.match(previous, '^([^|]+)|([^|]+)|([^|]+)|([^|]+)|([^|]+)|([^|]+)|')
    local numericCode = tonumber(previousCode)
    local numericSequence = tonumber(previousSequence)
    local numericRemaining = tonumber(previousRemaining)
    local numericDecidedAt = tonumber(previousDecidedAt)
    if not previousUser or not numericCode or numericCode < 0 or numericCode > 8
            or not numericSequence or not numericRemaining or not numericDecidedAt then
        return response(CORRUPTED_STATE, -1, -1, 0)
    end
    if previousUser ~= userId then
        return response(IDEMPOTENCY_CONFLICT, -1, -1, numericDecidedAt)
    end
    return response(numericCode, numericSequence, numericRemaining, numericDecidedAt)
end

local metadata = redis.call('HMGET', KEYS[1], 'openAt', 'closeAt', 'expireAt')
local openAt = tonumber(metadata[1])
local closeAt = tonumber(metadata[2])
local expireAt = tonumber(metadata[3])
local stock = tonumber(redis.call('GET', KEYS[2]))
local currentSequence = tonumber(redis.call('GET', KEYS[3]))
local redisTime = redis.call('TIME')
local now = (tonumber(redisTime[1]) * 1000) + math.floor(tonumber(redisTime[2]) / 1000)

if not openAt or not closeAt or not expireAt or not stock or not currentSequence
        or not bitOffset or bitOffset < 0 or bitOffset % 1 ~= 0 then
    return response(CORRUPTED_STATE, -1, -1, now)
end

-- Redis 서버 시각 기반 캠페인 구간 판정
if now < openAt then
    remember(EVENT_NOT_OPEN, -1, stock, now)
    return response(EVENT_NOT_OPEN, -1, stock, now)
end
if now >= closeAt then
    remember(EVENT_CLOSED, -1, stock, now)
    return response(EVENT_CLOSED, -1, stock, now)
end

-- Bitmap 기반 저장 대기 포함 사용자 중복 차단
if redis.call('GETBIT', KEYS[4], bitOffset) == 1 then
    remember(ALREADY_ISSUED, -1, stock, now)
    return response(ALREADY_ISSUED, -1, stock, now)
end

-- 재고 음수 진입 차단
if stock <= 0 then
    remember(SOLD_OUT, -1, stock, now)
    return response(SOLD_OUT, -1, stock, now)
end

-- 단일 Lua 실행 내 재고·중복·순번·비동기 큐 원자적 변경
local remaining = redis.call('DECR', KEYS[2])
redis.call('SETBIT', KEYS[4], bitOffset, 1)
local sequence = redis.call('INCR', KEYS[3])
local streamId = redis.call('XADD', KEYS[6], '*',
        'requestId', requestId,
        'campaignId', campaignId,
        'userId', userId,
        'issueSequence', tostring(sequence),
        'decidedAt', tostring(now))

-- 동적 생성 키의 캠페인 공통 만료 시각 적용
if bitmapType == 'none' then
    redis.call('PEXPIREAT', KEYS[4], expireAt)
end
if streamType == 'none' then
    redis.call('PEXPIREAT', KEYS[6], expireAt)
end

remember(ACCEPTED, sequence, remaining, now, streamId)
return response(ACCEPTED, sequence, remaining, now)
