-- One bounded hash per merchant/policy. TTL starts at the first admitted intent.
-- Only admitted intents consume capacity; their markers deduplicate ambiguous retries.
local limit=tonumber(ARGV[1])
local window=tonumber(ARGV[2])
if not limit or limit<1 or limit>1000 or limit~=math.floor(limit) or not window or window<1000 or window>3600000 or window~=math.floor(window) then
    return redis.error_reply('INVALID_ADMISSION_POLICY')
end
if string.len(ARGV[3])~=64 or not string.match(ARGV[3],'^%x+$') then return redis.error_reply('INVALID_ADMISSION_ID') end
local exists=redis.call('EXISTS',KEYS[1])
local used=0
local ttl=window
if exists==1 then
    ttl=redis.call('PTTL',KEYS[1])
    local value=redis.call('HGET',KEYS[1],'used')
    used=tonumber(value)
    if ttl<=0 or ttl>window or not used or used<1 or used>limit or used~=math.floor(used) then
        return redis.error_reply('INVALID_ADMISSION_STATE')
    end
    local prior=redis.call('HGET',KEYS[1],'r:'..ARGV[3])
    if prior then
        local count=tonumber(prior)
        if not count or count<1 or count>used or count~=math.floor(count) then return redis.error_reply('INVALID_ADMISSION_STATE') end
        return {1,count,ttl}
    end
end
if used>=limit then return {0,used,ttl} end
local next=used+1
redis.call('HSET',KEYS[1],'used',next,'r:'..ARGV[3],next)
if exists==0 then redis.call('PEXPIRE',KEYS[1],window) end
return {1,next,ttl}
