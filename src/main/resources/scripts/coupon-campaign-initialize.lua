-- KEYS[1]: 캠페인 메타데이터 Hash
-- KEYS[2]: 잔여 재고 String
-- KEYS[3]: 발급 순번 String
-- KEYS[4]: 요청 상태 Hash
-- KEYS[5]: 비동기 저장 대기 Stream
-- ARGV[1]: 총 재고
-- ARGV[2]: 오픈 시각 epoch millis
-- ARGV[3]: 마감 시각 epoch millis
-- ARGV[4]: 보존 만료 시각 epoch millis
-- ARGV[5]: Bitmap 세그먼트당 비트 수

local INITIALIZED = 0
local ALREADY_INITIALIZED = 1
local CONFIGURATION_CONFLICT = 2
local INVALID_CONFIGURATION = 3
local INTERNAL_WRITE_ERROR = 4

local DIAG_NONE = 0
local DIAG_METADATA_WRITE = 311
local DIAG_STOCK_WRITE = 312
local DIAG_SEQUENCE_WRITE = 313
local DIAG_REQUESTS_WRITE = 314
local DIAG_EXPIRE = {321, 322, 323, 324}

local SCHEMA_VERSION = 'v2'
local MAX_SAFE_INTEGER = 9007199254740991
local MAX_BITMAP_SEGMENT_BITS = 67108864

local totalStock = tonumber(ARGV[1])
local openAt = tonumber(ARGV[2])
local closeAt = tonumber(ARGV[3])
local expireAt = tonumber(ARGV[4])
local bitmapSegmentBits = tonumber(ARGV[5])

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

local function keyType(key)
    local result = redis.call('TYPE', key)
    return result['ok'] or result
end

local function cleanupCreatedKeys()
    redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4])
end

-- Redis 서버 시각 기준 초기 설정 검증
local redisTime = redis.call('TIME')
local now = (tonumber(redisTime[1]) * 1000)
        + math.floor(tonumber(redisTime[2]) / 1000)

if not isInteger(totalStock) or totalStock <= 0 or totalStock > MAX_SAFE_INTEGER
        or not isInteger(openAt) or not isInteger(closeAt) or not isInteger(expireAt)
        or openAt >= closeAt or closeAt >= expireAt or expireAt <= now
        or not isInteger(bitmapSegmentBits) or bitmapSegmentBits <= 0
        or bitmapSegmentBits > MAX_BITMAP_SEGMENT_BITS then
    return response(INVALID_CONFIGURATION)
end

-- 동일 설정 재실행의 무변경 검증
if redis.call('EXISTS', KEYS[1]) == 1 then
    if redis.call('EXISTS', KEYS[1], KEYS[2], KEYS[3], KEYS[4]) ~= 4
            or keyType(KEYS[1]) ~= 'hash'
            or keyType(KEYS[2]) ~= 'string'
            or keyType(KEYS[3]) ~= 'string'
            or keyType(KEYS[4]) ~= 'hash' then
        return response(CONFIGURATION_CONFLICT)
    end

    local current = redis.call('HMGET', KEYS[1],
            'totalStock', 'openAt', 'closeAt', 'expireAt',
            'bitmapSegmentBits', 'schemaVersion')
    local stock = tonumber(redis.call('GET', KEYS[2]))
    local sequence = tonumber(redis.call('GET', KEYS[3]))
    local requestSchema = redis.call('HGET', KEYS[4], '__schema__')
    local streamType = keyType(KEYS[5])

    if current[1] ~= ARGV[1] or current[2] ~= ARGV[2]
            or current[3] ~= ARGV[3] or current[4] ~= ARGV[4]
            or current[5] ~= ARGV[5] or current[6] ~= SCHEMA_VERSION
            or not isInteger(stock) or stock < 0 or stock > totalStock
            or not isInteger(sequence) or sequence < 0
            or requestSchema ~= SCHEMA_VERSION
            or (streamType ~= 'none' and streamType ~= 'stream')
            or (sequence > 0 and streamType ~= 'stream') then
        return response(CONFIGURATION_CONFLICT)
    end

    -- 핵심 키와 생성된 Stream의 만료 정책 검증
    for index = 1, 4 do
        if redis.call('PTTL', KEYS[index]) <= 0 then
            return response(CONFIGURATION_CONFLICT)
        end
    end
    if streamType == 'stream' and redis.call('PTTL', KEYS[5]) <= 0 then
        return response(CONFIGURATION_CONFLICT)
    end
    return response(ALREADY_INITIALIZED)
end

-- 부분 잔존 상태 기반 재고 덮어쓰기 차단
if redis.call('EXISTS', KEYS[2], KEYS[3], KEYS[4], KEYS[5]) > 0 then
    return response(CONFIGURATION_CONFLICT)
end

-- 오류 시 신규 핵심 키 제거 기반 부분 초기화 방지
local result = redis.pcall('HSET', KEYS[1],
        'totalStock', ARGV[1],
        'openAt', ARGV[2],
        'closeAt', ARGV[3],
        'expireAt', ARGV[4],
        'bitmapSegmentBits', ARGV[5],
        'schemaVersion', SCHEMA_VERSION)
if isError(result) then
    cleanupCreatedKeys()
    return response(INTERNAL_WRITE_ERROR, DIAG_METADATA_WRITE, errorDetail(result))
end

result = redis.pcall('SET', KEYS[2], ARGV[1])
if isError(result) then
    cleanupCreatedKeys()
    return response(INTERNAL_WRITE_ERROR, DIAG_STOCK_WRITE, errorDetail(result))
end

result = redis.pcall('SET', KEYS[3], '0')
if isError(result) then
    cleanupCreatedKeys()
    return response(INTERNAL_WRITE_ERROR, DIAG_SEQUENCE_WRITE, errorDetail(result))
end

result = redis.pcall('HSET', KEYS[4], '__schema__', SCHEMA_VERSION)
if isError(result) then
    cleanupCreatedKeys()
    return response(INTERNAL_WRITE_ERROR, DIAG_REQUESTS_WRITE, errorDetail(result))
end

-- 캠페인 보존 종료 시각 기준 핵심 키 만료 갱신
for index = 1, 4 do
    result = redis.pcall('PEXPIREAT', KEYS[index], expireAt)
    if isError(result) or result ~= 1 then
        cleanupCreatedKeys()
        return response(INTERNAL_WRITE_ERROR, DIAG_EXPIRE[index], errorDetail(result))
    end
end

return response(INITIALIZED)
