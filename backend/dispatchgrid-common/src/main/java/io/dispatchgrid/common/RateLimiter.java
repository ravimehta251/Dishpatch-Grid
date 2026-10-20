package io.dispatchgrid.common;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Fixed one-minute windows measured on the Redis server. Atomic across API replicas. */
@Component
public class RateLimiter {
  private final StringRedisTemplate redis;
  private static final DefaultRedisScript<Long> SCRIPT =
      new DefaultRedisScript<>(
          """
        local t = redis.call('TIME')
        local window = math.floor(tonumber(t[1]) / 60)
        local key = KEYS[1] .. ':' .. window
        local count = redis.call('INCR', key)
        if count == 1 then redis.call('EXPIRE', key, 120) end
        if count > tonumber(ARGV[1]) then return 60 - (tonumber(t[1]) % 60) end
        return 0
        """,
          Long.class);

  public RateLimiter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public void check(UUID tenantId, int quota) {
    checkKey("ingest:{" + tenantId + "}", quota);
  }

  public void checkKey(String key, int quota) {
    try {
      Long wait =
          redis.execute(SCRIPT, List.of("dispatchgrid:rate:" + key), Integer.toString(quota));
      if (wait == null) throw new ApiException(503, "Rate limiter unavailable; retry later");
      if (wait > 0) throw new RateLimitException(wait);
    } catch (DataAccessException e) {
      throw new ApiException(503, "Rate limiter unavailable; retry later");
    }
  }
}
