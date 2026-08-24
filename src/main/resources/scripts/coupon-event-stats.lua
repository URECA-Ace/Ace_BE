-- KEYS[1] campaign metadata HASH
-- KEYS[2] remaining stock STRING
--
-- return: {code, totalStock, allocatedQuantity, remainingStock, statusCode, observedAt,
--          confirmedQuantity, pendingQuantity}
-- code: 0=SUCCESS, 1=CAMPAIGN_NOT_INITIALIZED, 2=CORRUPTED_STATE
-- statusCode: 0=SCHEDULED, 1=OPEN, 2=SOLD_OUT, 3=CLOSED
--
-- allocatedQuantity 는 판정 완료 수, confirmedQuantity 는 MySQL 저장까지 끝난 수
-- 그 차이인 pendingQuantity 가 저장 파이프라인 지연 관측 지표

local SUCCESS = 0
local CAMPAIGN_NOT_INITIALIZED = 1
local CORRUPTED_STATE = 2

local SCHEDULED = 0
local OPEN = 1
local SOLD_OUT = 2
local CLOSED = 3

local function isInteger(value)
    return value and value % 1 == 0
end

local function isError(result)
    return type(result) == 'table' and result['err'] ~= nil
end

local redisTime = redis.call('TIME')
local observedAt = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)

local function errorResponse(code)
    return {code, -1, -1, -1, -1, observedAt, -1, -1}
end

if #KEYS ~= 2 then
    return errorResponse(CORRUPTED_STATE)
end

local metadata = redis.pcall('HMGET', KEYS[1],
        'totalStock', 'openAt', 'closeAt', 'confirmedQuantity')
local stockValue = redis.pcall('GET', KEYS[2])
if isError(metadata) or isError(stockValue) then
    return errorResponse(CORRUPTED_STATE)
end

local metadataMissing = not metadata[1] and not metadata[2] and not metadata[3]
if metadataMissing and not stockValue then
    return errorResponse(CAMPAIGN_NOT_INITIALIZED)
end
if not metadata[1] or not metadata[2] or not metadata[3] or not stockValue then
    return errorResponse(CORRUPTED_STATE)
end

local totalStock = tonumber(metadata[1])
local openAt = tonumber(metadata[2])
local closeAt = tonumber(metadata[3])
local remainingStock = tonumber(stockValue)
if not isInteger(totalStock) or totalStock <= 0
        or not isInteger(openAt) or not isInteger(closeAt) or openAt >= closeAt
        or not isInteger(remainingStock) or remainingStock < 0 or remainingStock > totalStock then
    return errorResponse(CORRUPTED_STATE)
end

local statusCode
if observedAt < openAt then
    statusCode = SCHEDULED
elseif observedAt >= closeAt then
    statusCode = CLOSED
elseif remainingStock == 0 then
    statusCode = SOLD_OUT
else
    statusCode = OPEN
end

local allocatedQuantity = totalStock - remainingStock

-- 확정 처리 전 캠페인에는 필드가 X
-- 없는 것은 결함이 아니라 0
local confirmedQuantity = tonumber(metadata[4]) or 0
if not isInteger(confirmedQuantity)
        or confirmedQuantity < 0 or confirmedQuantity > allocatedQuantity then
    return errorResponse(CORRUPTED_STATE)
end
local pendingQuantity = allocatedQuantity - confirmedQuantity

return {SUCCESS, totalStock, allocatedQuantity, remainingStock, statusCode, observedAt,
        confirmedQuantity, pendingQuantity}
