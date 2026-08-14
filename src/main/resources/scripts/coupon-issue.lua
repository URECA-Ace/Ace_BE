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

local function response(code, sequence, remaining, decidedAt)
    return {code, sequence or -1, remaining or -1, decidedAt}
end

local function requestStatusCode(code)
    if code == ACCEPTED then return 0 end
    if code == SOLD_OUT then return 5 end
    if code == ALREADY_ISSUED then return 6 end
    if code == EVENT_NOT_OPEN then return 7 end
    if code == EVENT_CLOSED then return 8 end
    return -1
end

-- 숫자 상태 코드 기반 요청 값 직렬화 크기 축소
local function remember(code, sequence, remaining, decidedAt)
    local packed = table.concat({
        userId,
        tostring(code),
        tostring(requestStatusCode(code)),
        tostring(sequence or -1),
        tostring(remaining or -1),
        tostring(decidedAt)
    }, '|')
    redis.call('HSET', KEYS[5], requestId, packed)
end

-- 멱등 재요청 우선 반환 기반 불필요한 키 검증 제거
-- 단일 HMGET 기반 requestId와 스키마 동시 검증
local requestState = redis.pcall('HMGET', KEYS[5], requestId, '__schema__')
if requestState['err'] then
    return response(CORRUPTED_STATE, -1, -1, 0)
end
local previous = requestState[1]
local requestSchema = requestState[2]
if previous then
    if requestSchema ~= 'v1' then
        return response(CORRUPTED_STATE, -1, -1, 0)
    end
    -- 구형 7필드 요청 상태 호환
    local previousUser, previousCode, _, previousSequence, previousRemaining, previousDecidedAt =
            string.match(previous, '^([^|]+)|([^|]+)|([^|]+)|([^|]+)|([^|]+)|([^|]+)')
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

-- 실제 읽기 명령 기반 정적 키 TYPE 호출 제거
local metadata = redis.pcall('HMGET', KEYS[1],
        'openAt', 'closeAt', 'expireAt', 'schemaVersion')
local stockValue = redis.pcall('GET', KEYS[2])
local sequenceValue = redis.pcall('GET', KEYS[3])
if metadata['err'] or (type(stockValue) == 'table' and stockValue['err'])
        or (type(sequenceValue) == 'table' and sequenceValue['err']) then
    return response(CORRUPTED_STATE, -1, -1, 0)
end
-- HMGET 공백 결과 기반 미초기화 판정
if not metadata[1] and not metadata[2] and not metadata[3] and not metadata[4] then
    return response(CAMPAIGN_NOT_INITIALIZED, -1, -1, 0)
end
local openAt = tonumber(metadata[1])
local closeAt = tonumber(metadata[2])
local expireAt = tonumber(metadata[3])
local schemaVersion = metadata[4]
local stock = tonumber(stockValue)
local currentSequence = tonumber(sequenceValue)
local redisTime = redis.call('TIME')
local now = (tonumber(redisTime[1]) * 1000) + math.floor(tonumber(redisTime[2]) / 1000)

if not openAt or not closeAt or not expireAt or schemaVersion ~= '1'
        or requestSchema ~= 'v1'
        or not stock or not currentSequence
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
local issued = redis.pcall('GETBIT', KEYS[4], bitOffset)
if type(issued) == 'table' and issued['err'] then
    return response(CORRUPTED_STATE, -1, -1, now)
end
if issued == 1 then
    remember(ALREADY_ISSUED, -1, stock, now)
    return response(ALREADY_ISSUED, -1, stock, now)
end

-- 재고 음수 진입 차단
if stock <= 0 then
    remember(SOLD_OUT, -1, stock, now)
    return response(SOLD_OUT, -1, stock, now)
end

-- 승인 경로 한정 동적 키 존재 여부와 Stream 타입 검증
local bitmapMissing = redis.call('EXISTS', KEYS[4]) == 0
local streamTypeResult = redis.call('TYPE', KEYS[6])
local streamType = streamTypeResult['ok'] or streamTypeResult
if streamType ~= 'none' and streamType ~= 'stream' then
    return response(CORRUPTED_STATE, -1, -1, now)
end

-- 단일 Lua 실행 내 재고·중복·순번·비동기 큐 원자적 변경
local remaining = redis.call('DECR', KEYS[2])
redis.call('SETBIT', KEYS[4], bitOffset, 1)
local sequence = redis.call('INCR', KEYS[3])
-- 미사용 Stream ID 요청 상태 저장 제거
redis.call('XADD', KEYS[6], '*',
        'requestId', requestId,
        'campaignId', campaignId,
        'userId', userId,
        'issueSequence', tostring(sequence),
        'decidedAt', tostring(now))

-- 동적 생성 키의 캠페인 공통 만료 시각 적용
if bitmapMissing then
    redis.call('PEXPIREAT', KEYS[4], expireAt)
end
if streamType == 'none' then
    redis.call('PEXPIREAT', KEYS[6], expireAt)
end

remember(ACCEPTED, sequence, remaining, now)
return response(ACCEPTED, sequence, remaining, now)
