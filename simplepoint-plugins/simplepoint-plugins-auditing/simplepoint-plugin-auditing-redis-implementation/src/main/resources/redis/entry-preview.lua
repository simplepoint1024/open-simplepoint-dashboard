-- Fixed, single-key, read-only preview. Redis 7+ (the platform compose baseline).
local key = KEYS[1]
local kind = redis.call('TYPE', key).ok
if kind == 'none' then return {} end
local ttl = redis.call('TTL', key)
local commands = {string='STRLEN', hash='HLEN', list='LLEN', set='SCARD', zset='ZCARD', stream='XLEN'}
local size = commands[kind] and redis.call(commands[kind], key) or -1
local budget = tonumber(ARGV[2])
local maxItems = tonumber(ARGV[3])
local truncated = 0
local sampled = 0
local remaining = budget
local atoms = 400
local function bounded(value)
  if type(value) == 'table' then
    local output = {}
    for _, item in ipairs(value) do
      if remaining <= 0 or atoms <= 0 then truncated = 1; break end
      output[#output + 1] = bounded(item)
    end
    return output
  end
  local text = tostring(value)
  local length = math.min(#text, remaining)
  if length < #text then truncated = 1 end
  remaining = remaining - length
  atoms = atoms - 1
  return string.sub(text, 1, length)
end
local values = {}
if ARGV[1] == '1' then
  if kind == 'string' then
    values = redis.call('GETRANGE', key, 0, budget - 1)
    if size > budget then truncated = 1 end
  elseif kind == 'hash' or kind == 'set' then
    -- COUNT is a hint, not a hard server allocation limit. Bound the returned wire payload.
    local result = redis.call(kind == 'hash' and 'HSCAN' or 'SSCAN', key, 0, 'COUNT', maxItems)
    local width = kind == 'hash' and 2 or 1
    local selected = {}
    for index = 1, math.min(#result[2], maxItems * width), width do
      selected[#selected + 1] = kind == 'hash' and {result[2][index], result[2][index + 1]} or result[2][index]
    end
    values = bounded(selected)
    if result[1] ~= '0' or #selected < size then sampled = 1 end
  elseif kind == 'list' or kind == 'zset' then
    for index = 0, math.min(size, maxItems) - 1 do
      if remaining <= 0 or atoms <= 0 then truncated = 1; break end
      local value = kind == 'list' and redis.call('LINDEX', key, index)
        or redis.call('ZRANGE', key, index, index, 'WITHSCORES')
      values[#values + 1] = bounded(value)
    end
    if #values < size then sampled = 1 end
  elseif kind == 'stream' then
    values = bounded(redis.call('XRANGE', key, '-', '+', 'COUNT', math.min(maxItems, 20)))
    if #values < size then sampled = 1 end
  end
end
return {kind, ttl, size, sampled, truncated, values}
