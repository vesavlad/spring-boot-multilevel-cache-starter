package io.github.suppie.spring.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.suppie.spring.cache.MultiLevelCacheMetrics.RedisCallOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MultiLevelCacheMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @Test
  void readResultsAreCountedPerCache() {
    MultiLevelCacheMetrics metrics = MultiLevelCacheMetrics.create(registry, "products");

    metrics.localHit();
    metrics.localHit();
    metrics.remoteHit();
    metrics.miss();

    assertThat(gets("local_hit").count()).isEqualTo(2d);
    assertThat(gets("remote_hit").count()).isEqualTo(1d);
    assertThat(gets("miss").count()).isEqualTo(1d);
  }

  @Test
  void redisCallsAreTimedByOperationAndOutcome() {
    MultiLevelCacheMetrics metrics = MultiLevelCacheMetrics.create(registry, "products");

    metrics.redisCall("read", RedisCallOutcome.SUCCESS, 1_000_000L);
    metrics.redisCall("read", RedisCallOutcome.SUCCESS, 3_000_000L);
    metrics.redisCall("write", RedisCallOutcome.REJECTED, 10L);

    Timer readSuccess = redisCalls("read", "success");
    assertThat(readSuccess.count()).isEqualTo(2L);
    assertThat(readSuccess.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(4d);
    assertThat(redisCalls("write", "rejected").count()).isEqualTo(1L);
  }

  @Test
  void invalidationsAreCountedByDirection() {
    MultiLevelCacheMetrics metrics = MultiLevelCacheMetrics.create(registry, "products");

    metrics.invalidationSent();
    metrics.invalidationReceived();
    metrics.invalidationReceived();

    assertThat(invalidations("sent").count()).isEqualTo(1d);
    assertThat(invalidations("received").count()).isEqualTo(2d);
  }

  @Test
  void rejectedInvalidationsAreCountedWithoutCacheTag() {
    MultiLevelCacheMetrics.invalidationRejected(registry);
    MultiLevelCacheMetrics.invalidationRejected(registry);

    Counter rejected = registry.get("cache.multilevel.invalidations.rejected").counter();
    assertThat(rejected.count()).isEqualTo(2d);
    assertThat(rejected.getId().getTags()).isEmpty();
  }

  @Test
  void noopRecordsNothingAndNeverFails() {
    MultiLevelCacheMetrics metrics = MultiLevelCacheMetrics.NOOP;

    assertThatCode(
            () -> {
              metrics.localHit();
              metrics.remoteHit();
              metrics.miss();
              metrics.redisCall("read", RedisCallOutcome.ERROR, 1L);
              metrics.invalidationSent();
              metrics.invalidationReceived();
              MultiLevelCacheMetrics.invalidationRejected(null);
            })
        .doesNotThrowAnyException();
    assertThat(registry.getMeters()).isEmpty();
  }

  private Counter gets(String result) {
    return registry
        .get("cache.multilevel.gets")
        .tags("cache", "products", "result", result)
        .counter();
  }

  private Timer redisCalls(String operation, String outcome) {
    return registry
        .get("cache.multilevel.redis.calls")
        .tags("cache", "products", "operation", operation, "outcome", outcome)
        .timer();
  }

  private Counter invalidations(String direction) {
    return registry
        .get("cache.multilevel.invalidations")
        .tags("cache", "products", "direction", direction)
        .counter();
  }
}
