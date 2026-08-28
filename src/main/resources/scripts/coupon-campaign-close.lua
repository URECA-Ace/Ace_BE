-- KEYS[1]: campaign metadata HASH
-- return: {code, closedAt, diagnosticStage, diagnosticMessage}
-- closedAt: CLOSED 시 Redis 서버가 관측한 마감 시각,
--           ALREADY_CLOSED 시 metadata에 저장된 기존 마감 시각
-- code: 0=CLOSED, 1=ALREADY_CLOSED, 2=NOT_INITIALIZED,
--       3=INVALID_STATE, 4=CORRUPTED_STATE, 5=INTERNAL_WRITE_ERROR

local CLOSED = 0
local ALREADY_CLOSED = 1
local NOT_INITIALIZED = 2
local INVALID_STATE = 3
local CORRUPTED_STATE = 4
local INTERNAL_WRITE_ERROR = 5

local DIAG_NONE = 0
local DIAG_METADATA_READ = 501
local DIAG_METADATA_WRITE = 511
local SCHEMA_VERSION = 'v2'

local function isInteger(value)
    return value and value % 1 == 0
end

local function isError(result)
    return type(result) == 'table' and result['err'] ~= nil
end

local function detail(result)
    if isError(result) then
        return string.sub(tostring(result['err']), 1, 256)
    end
    return string.sub('unexpected result: ' .. tostring(result), 1, 256)
end

local redisTime = redis.call('TIME')
local observedAt = tonumber(redisTime[1]) * 1000
        + math.floor(tonumber(redisTime[2]) / 1000)

local function response(code, stage, message, closedAt)
    return {code, closedAt or observedAt, stage or DIAG_NONE, message or ''}
end

local metadata = redis.pcall('HMGET', KEYS[1],
        'totalStock', 'openAt', 'closeAt', 'expireAt', 'schemaVersion')
if isError(metadata) then
    return response(CORRUPTED_STATE, DIAG_METADATA_READ, detail(metadata))
end

if not metadata[1] and not metadata[2] and not metadata[3]
        and not metadata[4] and not metadata[5] then
    return response(NOT_INITIALIZED)
end

local totalStock = tonumber(metadata[1])
local openAt = tonumber(metadata[2])
local closeAt = tonumber(metadata[3])
local expireAt = tonumber(metadata[4])
local schemaVersion = metadata[5]

if schemaVersion ~= SCHEMA_VERSION
        or not isInteger(totalStock) or totalStock <= 0
        or not isInteger(openAt) or not isInteger(closeAt)
        or not isInteger(expireAt) or openAt >= closeAt
        or closeAt >= expireAt or observedAt >= expireAt then
    return response(CORRUPTED_STATE)
end

if observedAt <= openAt then
    return response(INVALID_STATE)
end

if observedAt >= closeAt then
    return response(ALREADY_CLOSED, nil, nil, closeAt)
end

local writeResult = redis.pcall('HSET', KEYS[1], 'closeAt', tostring(observedAt))
if isError(writeResult) or writeResult ~= 0 then
    return response(INTERNAL_WRITE_ERROR, DIAG_METADATA_WRITE, detail(writeResult))
end

return response(CLOSED)
