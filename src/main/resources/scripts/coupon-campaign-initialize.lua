-- KEYS[1]: 캠페인 메타데이터 Hash
-- KEYS[2]: 잔여 재고 String
-- KEYS[3]: 발급 순번 String
-- KEYS[4]: 요청 상태 Hash
-- ARGV[1]: 총 재고
-- ARGV[2]: 오픈 시각 epoch millis
-- ARGV[3]: 마감 시각 epoch millis
-- ARGV[4]: 만료 시각 epoch millis

local INITIALIZED = 0
local ALREADY_INITIALIZED = 1
local CONFIGURATION_CONFLICT = 2
local INVALID_CONFIGURATION = 3

local totalStock = tonumber(ARGV[1])
local openAt = tonumber(ARGV[2])
local closeAt = tonumber(ARGV[3])
local expireAt = tonumber(ARGV[4])
local redisTime = redis.call('TIME')
local now = (tonumber(redisTime[1]) * 1000) + math.floor(tonumber(redisTime[2]) / 1000)

-- 초기 설정 유효성 검증
if not totalStock or totalStock <= 0 or totalStock % 1 ~= 0
        or not openAt or not closeAt or not expireAt
        or openAt >= closeAt or closeAt >= expireAt or expireAt <= now then
    return INVALID_CONFIGURATION
end

-- 동일 설정 재실행의 무변경 처리
if redis.call('EXISTS', KEYS[1]) == 1 then
    local current = redis.call('HMGET', KEYS[1], 'totalStock', 'openAt', 'closeAt', 'expireAt')
    if current[1] == ARGV[1] and current[2] == ARGV[2]
            and current[3] == ARGV[3] and current[4] == ARGV[4] then
        return ALREADY_INITIALIZED
    end
    return CONFIGURATION_CONFLICT
end

-- 부분 잔존 키 기반 재고 덮어쓰기 차단
if redis.call('EXISTS', KEYS[2], KEYS[3], KEYS[4]) > 0 then
    return CONFIGURATION_CONFLICT
end

redis.call('SET', KEYS[2], ARGV[1])
redis.call('SET', KEYS[3], '0')
redis.call('HSET', KEYS[4], '__schema__', 'v1')
redis.call('HSET', KEYS[1],
        'totalStock', ARGV[1],
        'openAt', ARGV[2],
        'closeAt', ARGV[3],
        'expireAt', ARGV[4],
        'schemaVersion', '1')

-- 캠페인 종료 후 일괄 정리용 절대 만료 시각
redis.call('PEXPIREAT', KEYS[1], expireAt)
redis.call('PEXPIREAT', KEYS[2], expireAt)
redis.call('PEXPIREAT', KEYS[3], expireAt)
redis.call('PEXPIREAT', KEYS[4], expireAt)

return INITIALIZED
