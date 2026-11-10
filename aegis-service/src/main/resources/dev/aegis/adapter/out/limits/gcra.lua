-- GCRA (see Gcra.java, the same arithmetic): one value per key, its theoretical arrival time (TAT)
-- in microseconds on this Redis server's clock, so every Aegis instance shares one time base.
-- KEYS[1] the key · ARGV[1] emission interval (µs) · ARGV[2] burst
-- Returns {1, 0} when allowed, {0, retry-after µs} when refused.
local interval = tonumber(ARGV[1])
local horizon = interval * tonumber(ARGV[2])
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000000 + tonumber(t[2])
local tat = tonumber(redis.call('GET', KEYS[1]))
-- A TAT beyond the horizon cannot be reached by counting: the clock went back. Start afresh.
if not tat or tat < now or tat - now > horizon then
  tat = now
end
local nxt = tat + interval
if nxt - now > horizon then
  return {0, nxt - now - horizon}
end
-- Kept only while it still limits anything, so idle keys cost no memory.
redis.call('SET', KEYS[1], string.format('%d', nxt), 'PX', math.ceil((nxt - now) / 1000) + 1000)
return {1, 0}
