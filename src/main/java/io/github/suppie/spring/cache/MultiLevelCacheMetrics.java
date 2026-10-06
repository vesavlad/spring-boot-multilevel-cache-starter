/*
 * MIT License
 *
 * Copyright (c) 2024 Roman Khlebnov
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.suppie.spring.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/** Micrometer meters for one multi-level cache; {@link #NOOP} records nothing. */
public final class MultiLevelCacheMetrics {

  static final String GETS = "cache.multilevel.gets";
  static final String REDIS_CALLS = "cache.multilevel.redis.calls";
  static final String INVALIDATIONS = "cache.multilevel.invalidations";
  static final String INVALIDATIONS_REJECTED = INVALIDATIONS + ".rejected";

  /** Metrics that record nothing, used when no meter registry is available. */
  public static final MultiLevelCacheMetrics NOOP = new MultiLevelCacheMetrics(null, "");

  private final @Nullable MeterRegistry registry;
  private final String cacheName;
  private final @Nullable Counter localHits;
  private final @Nullable Counter remoteHits;
  private final @Nullable Counter misses;
  private final @Nullable Counter invalidationsSent;
  private final @Nullable Counter invalidationsReceived;
  private final Map<String, Timer> redisTimers = new ConcurrentHashMap<>();

  private MultiLevelCacheMetrics(@Nullable MeterRegistry registry, String cacheName) {
    this.registry = registry;
    this.cacheName = cacheName;
    this.localHits = registry == null ? null : gets(registry, cacheName, "local_hit");
    this.remoteHits = registry == null ? null : gets(registry, cacheName, "remote_hit");
    this.misses = registry == null ? null : gets(registry, cacheName, "miss");
    this.invalidationsSent = registry == null ? null : invalidations(registry, cacheName, "sent");
    this.invalidationsReceived =
        registry == null ? null : invalidations(registry, cacheName, "received");
  }

  /**
   * Registers the meters for one cache.
   *
   * @param registry registry to publish meters to
   * @param cacheName cache name used as the {@code cache} tag
   * @return metrics recording into {@code registry}
   */
  public static MultiLevelCacheMetrics create(MeterRegistry registry, String cacheName) {
    return new MultiLevelCacheMetrics(registry, cacheName);
  }

  /** Counts an inbound invalidation message that could not be decoded or applied. */
  static void invalidationRejected(@Nullable MeterRegistry registry) {
    if (registry != null) {
      Counter.builder(INVALIDATIONS_REJECTED)
          .description("Inbound cache invalidation messages that could not be processed")
          .register(registry)
          .increment();
    }
  }

  void localHit() {
    increment(localHits);
  }

  void remoteHit() {
    increment(remoteHits);
  }

  void miss() {
    increment(misses);
  }

  void invalidationSent() {
    increment(invalidationsSent);
  }

  void invalidationReceived() {
    increment(invalidationsReceived);
  }

  void redisCall(String operation, RedisCallOutcome outcome, long nanos) {
    MeterRegistry meterRegistry = registry;
    if (meterRegistry == null) {
      return;
    }

    redisTimers
        .computeIfAbsent(
            operation + '|' + outcome.tag(),
            ignored ->
                Timer.builder(REDIS_CALLS)
                    .description("Redis calls made by the multi-level cache")
                    .tag("cache", cacheName)
                    .tag("operation", operation)
                    .tag("outcome", outcome.tag())
                    .register(meterRegistry))
        .record(nanos, TimeUnit.NANOSECONDS);
  }

  private static Counter gets(MeterRegistry registry, String cacheName, String result) {
    return Counter.builder(GETS)
        .description("Cache reads by the tier that served them")
        .tag("cache", cacheName)
        .tag("result", result)
        .register(registry);
  }

  private static Counter invalidations(MeterRegistry registry, String cacheName, String direction) {
    return Counter.builder(INVALIDATIONS)
        .description("Cache invalidation messages exchanged between instances")
        .tag("cache", cacheName)
        .tag("direction", direction)
        .register(registry);
  }

  private static void increment(@Nullable Counter counter) {
    if (counter != null) {
      counter.increment();
    }
  }

  /** Result of one Redis call, used as the {@code outcome} tag. */
  enum RedisCallOutcome {
    SUCCESS,
    UNAVAILABLE,
    REJECTED,
    ERROR;

    String tag() {
      return name().toLowerCase(Locale.ROOT);
    }
  }
}
