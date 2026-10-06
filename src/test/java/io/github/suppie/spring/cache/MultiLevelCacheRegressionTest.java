package io.github.suppie.spring.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.support.NullValue;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.data.redis.serializer.StringRedisSerializer;

class MultiLevelCacheRegressionTest {

  @Test
  void localPutIfAbsentHitDoesNotCallRedis() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache cache = cache("cache", writer, RedisSerializer.json(), breaker("local-hit"));
    cache.getLocalCache().put(cache.toLocalKey("key"), "local");

    Cache.ValueWrapper result = cache.putIfAbsent("key", "candidate");

    assertThat(result).isNotNull();
    assertThat(result.get()).isEqualTo("local");
    assertThat(writer.gets).hasValue(0);
    assertThat(writer.putIfAbsentCalls).hasValue(0);
  }

  @Test
  void connectedColdCachesConvergeOnRedisWinner() throws Exception {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    writer.synchronizeNextPutIfAbsentCalls(2);
    MultiLevelCache first = cache("cache", writer, RedisSerializer.json(), breaker("first"));
    MultiLevelCache second = cache("cache", writer, RedisSerializer.json(), breaker("second"));

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Cache.ValueWrapper>> results =
          List.of(
              executor.submit(() -> first.putIfAbsent("key", "first")),
              executor.submit(() -> second.putIfAbsent("key", "second")));

      results.get(0).get();
      results.get(1).get();

      Object firstValue = first.get("key").get();
      Object secondValue = second.get("key").get();
      assertThat(firstValue).isEqualTo(secondValue);
      assertThat((Object) first.nativeGet("key")).isEqualTo(firstValue);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void loaderFailureDoesNotCountAsRedisFailure() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    CircuitBreaker breaker = breaker("loader");
    MultiLevelCache cache = cache("cache", writer, RedisSerializer.json(), breaker);

    assertThatThrownBy(
            () ->
                cache.get(
                    "key",
                    () -> {
                      throw new IllegalStateException("business failure");
                    }))
        .isInstanceOf(Cache.ValueRetrievalException.class);

    assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
  }

  @Test
  void serializationFailurePropagatesAndDoesNotPopulateLocalCache() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    RedisSerializer<Object> failingSerializer =
        new RedisSerializer<>() {
          @Override
          public byte[] serialize(Object value) throws SerializationException {
            throw new SerializationException("broken serializer");
          }

          @Override
          public Object deserialize(byte[] bytes) throws SerializationException {
            return null;
          }
        };
    MultiLevelCache cache = cache("cache", writer, failingSerializer, breaker("serialization"));

    assertThatThrownBy(() -> cache.put("key", "value")).isInstanceOf(SerializationException.class);
    assertThat(cache.getLocalCache().getIfPresent(cache.toLocalKey("key"))).isNull();
  }

  @Test
  void availabilityFailureLoadsAndStoresLocally() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    writer.failWith(new RedisConnectionFailureException("Redis is unavailable"));
    MultiLevelCache cache = cache("cache", writer, RedisSerializer.json(), breaker("availability"));

    assertThat(cache.get("key", () -> "fallback")).isEqualTo("fallback");
    assertThat(cache.getLocalCache().getIfPresent(cache.toLocalKey("key"))).isEqualTo("fallback");
  }

  @Test
  void availabilityFailureMakesPutIfAbsentStoreLocally() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    writer.failWith(new RedisConnectionFailureException("Redis is unavailable"));
    MultiLevelCache cache =
        cache("cache", writer, RedisSerializer.json(), breaker("put-if-absent-availability"));

    assertThat(cache.putIfAbsent("key", "fallback")).isNull();
    assertThat(cache.getLocalCache().getIfPresent(cache.toLocalKey("key"))).isEqualTo("fallback");
  }

  @Test
  void invalidationDuringRemoteReadDoesNotRepopulateLocalCache() throws Exception {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache cache = cache("cache", writer, RedisSerializer.json(), breaker("stale-read"));
    cache.nativePut("key", "stale");
    CountDownLatch getEntered = new CountDownLatch(1);
    CountDownLatch releaseGet = new CountDownLatch(1);
    writer.blockNextGet(getEntered, releaseGet);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Object> lookup = executor.submit(() -> cache.get("key").get());
      getEntered.await();
      cache.nativePut("key", "fresh");
      CountDownLatch invalidationStarted = new CountDownLatch(1);
      Future<?> invalidation =
          executor.submit(
              () -> {
                invalidationStarted.countDown();
                cache.invalidateLocalEntry(cache.toLocalKey("key"));
              });
      invalidationStarted.await();
      assertThat(invalidation.isDone()).isFalse();
      releaseGet.countDown();

      assertThat(lookup.get()).isEqualTo("stale");
      invalidation.get();
      assertThat(cache.getLocalCache().getIfPresent(cache.toLocalKey("key"))).isNull();
      assertThat(cache.get("key").get()).isEqualTo("fresh");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void cacheWideInvalidationWaitsForRemoteReadBeforeClearingLocalCache() throws Exception {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache cache =
        cache("cache", writer, RedisSerializer.json(), breaker("cache-wide-stale-read"));
    cache.nativePut("key", "stale");
    CountDownLatch getEntered = new CountDownLatch(1);
    CountDownLatch releaseGet = new CountDownLatch(1);
    writer.blockNextGet(getEntered, releaseGet);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Object> lookup = executor.submit(() -> cache.get("key").get());
      getEntered.await();
      CountDownLatch clearStarted = new CountDownLatch(1);
      Future<?> clear =
          executor.submit(
              () -> {
                clearStarted.countDown();
                cache.clear();
              });
      clearStarted.await();
      assertThat(clear.isDone()).isFalse();

      releaseGet.countDown();
      assertThat(lookup.get()).isEqualTo("stale");
      clear.get();

      assertThat(cache.getLocalCache().getIfPresent(cache.toLocalKey("key"))).isNull();
      assertThat((Object) cache.nativeGet("key")).isNull();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void differentKeysDoNotShareTheSameOperationLock() throws Exception {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache cache =
        cache("cache", writer, RedisSerializer.json(), breaker("different-key-locks"));
    cache.nativePut("first", "one");
    cache.nativePut("second", "two");
    CountDownLatch getEntered = new CountDownLatch(1);
    CountDownLatch releaseGet = new CountDownLatch(1);
    writer.blockNextGet(getEntered, releaseGet);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Object> blocked = executor.submit(() -> cache.get("first").get());
      getEntered.await();

      assertThat(cache.get("second").get()).isEqualTo("two");
      releaseGet.countDown();
      assertThat(blocked.get()).isEqualTo("one");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void inboundInvalidationWaitsForPutBeforeEvictingLocalValue() throws Exception {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache cache =
        cache("cache", writer, RedisSerializer.json(), breaker("put-invalidation-lock"));
    CountDownLatch putEntered = new CountDownLatch(1);
    CountDownLatch releasePut = new CountDownLatch(1);
    writer.blockNextPut(putEntered, releasePut);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> put = executor.submit(() -> cache.put("key", "value"));
      putEntered.await();
      CountDownLatch invalidationStarted = new CountDownLatch(1);
      Future<?> invalidation =
          executor.submit(
              () -> {
                invalidationStarted.countDown();
                cache.invalidateLocalEntry(cache.toLocalKey("key"));
              });
      invalidationStarted.await();
      assertThat(invalidation.isDone()).isFalse();

      releasePut.countDown();
      put.get();
      invalidation.get();

      assertThat((Object) cache.nativeGet("key")).isEqualTo("value");
      assertThat(cache.getLocalCache().getIfPresent(cache.toLocalKey("key"))).isNull();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void warmPutIfAbsentDoesNotAcquireTheKeyLock() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache cache =
        cache("cache", writer, RedisSerializer.json(), breaker("warm-put-if-absent"));
    String localKey = cache.toLocalKey("key");
    TrackingReentrantLock lock = new TrackingReentrantLock();
    cache.locks.put(localKey, lock);
    cache.getLocalCache().put(localKey, "existing");

    Cache.ValueWrapper result = cache.putIfAbsent("key", "candidate");

    assertThat(result).isNotNull();
    assertThat(result.get()).isEqualTo("existing");
    assertThat(lock.lockCalls).hasValue(0);
    assertThat(writer.putIfAbsentCalls).hasValue(0);
  }

  @Test
  void putIfAbsentRechecksLocalCacheAfterAcquiringTheKeyLock() throws Exception {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache cache =
        cache("cache", writer, RedisSerializer.json(), breaker("put-if-absent-recheck"));
    String localKey = cache.toLocalKey("key");
    TrackingReentrantLock lock = new TrackingReentrantLock();
    lock.holdByTest();
    cache.locks.put(localKey, lock);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Cache.ValueWrapper> result =
          executor.submit(() -> cache.putIfAbsent("key", "candidate"));
      lock.lockAttempted.await();
      cache.getLocalCache().put(localKey, "existing");
      lock.releaseByTest();

      assertThat(result.get()).isNotNull();
      assertThat(result.get().get()).isEqualTo("existing");
      assertThat(lock.lockCalls).hasValue(1);
      assertThat(writer.putIfAbsentCalls).hasValue(0);
    } finally {
      if (lock.isHeldByCurrentThread()) {
        lock.releaseByTest();
      }
      executor.shutdownNow();
    }
  }

  @Test
  void sameInstanceColdPutIfAbsentCallsRedisOnce() throws Exception {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache cache =
        cache("cache", writer, RedisSerializer.json(), breaker("same-instance-put-if-absent"));
    CountDownLatch putIfAbsentEntered = new CountDownLatch(1);
    CountDownLatch releasePutIfAbsent = new CountDownLatch(1);
    writer.blockNextPutIfAbsent(putIfAbsentEntered, releasePutIfAbsent);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Cache.ValueWrapper> first = executor.submit(() -> cache.putIfAbsent("key", "winner"));
      putIfAbsentEntered.await();
      Future<Cache.ValueWrapper> second =
          executor.submit(() -> cache.putIfAbsent("key", "candidate"));
      releasePutIfAbsent.countDown();

      assertThat(first.get()).isNull();
      assertThat(second.get()).isNotNull();
      assertThat(second.get().get()).isEqualTo("winner");
      assertThat(writer.putIfAbsentCalls).hasValue(1);
      assertThat((Object) cache.nativeGet("key")).isEqualTo("winner");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void lockStorageHasNoSizeOrTimeEviction() {
    MultiLevelCache cache =
        cache("cache", new TestRedisCacheWriter(), RedisSerializer.json(), breaker("locks"));

    assertThat(cache.locks.policy().eviction()).isEmpty();
    assertThat(cache.locks.policy().expireAfterAccess()).isEmpty();
  }

  @Test
  void equivalentConvertedKeysUseTheSameLockIdentity() {
    MultiLevelCache cache =
        cache(
            "cache", new TestRedisCacheWriter(), RedisSerializer.json(), breaker("lock-identity"));
    Object first = new EquivalentKey();
    Object second = new EquivalentKey();

    assertThat(cache.get(first, () -> "value")).isEqualTo("value");
    cache.invalidateLocalEntry(cache.toLocalKey(first));
    assertThat(cache.get(second, () -> "unused")).isEqualTo("value");

    assertThat(cache.locks.asMap().keySet()).containsExactly("same-key");
  }

  @Test
  void redisNullCachingIsDisabledByDefault() {
    MultiLevelCache cache =
        cache("cache", new TestRedisCacheWriter(), RedisSerializer.json(), breaker("nulls"));

    assertThat(cache.getCacheConfiguration().getAllowCacheNullValues()).isFalse();
    assertThat(cache.isAllowNullValues()).isFalse();
  }

  @Test
  void redisNullCachingFollowsProperty() {
    MultiLevelCache cache =
        cache(
            "cache",
            new TestRedisCacheWriter(),
            RedisSerializer.json(),
            breaker("nulls-enabled"),
            true);

    assertThat(cache.getCacheConfiguration().getAllowCacheNullValues()).isTrue();
    assertThat(cache.isAllowNullValues()).isTrue();
  }

  @Test
  void patternedClearInvalidatesEveryLocalEntry() {
    MultiLevelCache cache =
        cache("cache", new TestRedisCacheWriter(), RedisSerializer.json(), breaker("clear"));
    cache.put("first", "one");
    cache.put("second", "two");

    cache.clear("*");

    assertThat(cache.getLocalCache().estimatedSize()).isZero();
    assertThat((Object) cache.nativeGet("first")).isNull();
    assertThat((Object) cache.nativeGet("second")).isNull();
  }

  @Test
  void unpatternedClearDoesNotReenterPatternedClear() {
    CircuitBreaker breaker = breaker("unpatterned-clear");
    AtomicInteger successfulRedisCalls = new AtomicInteger();
    breaker.getEventPublisher().onSuccess(ignored -> successfulRedisCalls.incrementAndGet());
    MultiLevelCache cache =
        cache("cache", new TestRedisCacheWriter(), RedisSerializer.json(), breaker);
    cache.nativePut("key", "value");

    cache.clear();

    assertThat((Object) cache.nativeGet("key")).isNull();
    assertThat(successfulRedisCalls).hasValue(2);
  }

  @Test
  void invalidateUsesImmediateRedisOperation() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache cache =
        cache("cache", writer, RedisSerializer.json(), breaker("immediate-invalidate"));
    cache.nativePut("key", "value");

    boolean invalidated = cache.invalidate();

    assertThat(invalidated).isTrue();
    assertThat((Object) cache.nativeGet("key")).isNull();
    assertThat(writer.invalidates).hasValue(1);
    assertThat(writer.clears).hasValue(0);
  }

  @Test
  void nullMarkerIsAMissWhenNullCachingIsDisabled() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache enabled =
        cache("cache", writer, RedisSerializer.json(), breaker("marker-writer"), true);
    MultiLevelCache disabled =
        cache("cache", writer, RedisSerializer.json(), breaker("marker-reader"));
    enabled.nativePut("key", null);

    assertThat(disabled.get("key")).isNull();
    assertThat(disabled.getLocalCache().getIfPresent(disabled.toLocalKey("key"))).isNull();
  }

  @Test
  void loaderRunsOverNullMarkerWhenNullCachingIsDisabled() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache enabled =
        cache("cache", writer, RedisSerializer.json(), breaker("loader-marker-writer"), true);
    MultiLevelCache disabled =
        cache("cache", writer, RedisSerializer.json(), breaker("loader-marker-reader"));
    enabled.nativePut("key", null);

    assertThat(disabled.get("key", () -> "loaded")).isEqualTo("loaded");
    assertThat((Object) disabled.nativeGet("key")).isEqualTo("loaded");
  }

  @Test
  void putIfAbsentReplacesNullMarkerWhenNullCachingIsDisabled() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache enabled =
        cache("cache", writer, RedisSerializer.json(), breaker("pia-marker-writer"), true);
    MultiLevelCache disabled =
        cache("cache", writer, RedisSerializer.json(), breaker("pia-marker-reader"));
    enabled.nativePut("key", null);

    assertThat(disabled.putIfAbsent("key", "candidate")).isNull();
    assertThat((Object) disabled.nativeGet("key")).isEqualTo("candidate");
    assertThat(disabled.getLocalCache().getIfPresent(disabled.toLocalKey("key")))
        .isEqualTo("candidate");
  }

  @Test
  void loaderReturningNullIsCachedWhenNullCachingIsEnabled() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache cache =
        cache("cache", writer, RedisSerializer.json(), breaker("null-loader"), true);
    AtomicInteger loads = new AtomicInteger();

    assertThat(
            (Object)
                cache.get(
                    "key",
                    () -> {
                      loads.incrementAndGet();
                      return null;
                    }))
        .isNull();
    assertThat(
            (Object)
                cache.get(
                    "key",
                    () -> {
                      loads.incrementAndGet();
                      return null;
                    }))
        .isNull();

    assertThat(loads).hasValue(1);
    assertThat(cache.getLocalCache().getIfPresent(cache.toLocalKey("key")))
        .isSameAs(NullValue.INSTANCE);
    Cache.ValueWrapper wrapper = cache.get("key");
    assertThat(wrapper).isNotNull();
    assertThat(wrapper.get()).isNull();
  }

  @Test
  void cachedNullIsSharedThroughRedis() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache first =
        cache("cache", writer, RedisSerializer.json(), breaker("shared-null-first"), true);
    MultiLevelCache second =
        cache("cache", writer, RedisSerializer.json(), breaker("shared-null-second"), true);

    assertThat((Object) first.get("key", () -> null)).isNull();
    Object secondResult =
        second.get(
            "key",
            () -> {
              throw new AssertionError("Loader must not run for a cached null");
            });

    assertThat(secondResult).isNull();
    assertThat(second.getLocalCache().getIfPresent(second.toLocalKey("key")))
        .isSameAs(NullValue.INSTANCE);
  }

  @Test
  void putNullStoresMarkerWhenNullCachingIsEnabled() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache cache =
        cache("cache", writer, RedisSerializer.json(), breaker("put-null"), true);
    MultiLevelCache other =
        cache("cache", writer, RedisSerializer.json(), breaker("put-null-other"), true);
    cache.put("key", "value");

    cache.put("key", null);

    assertThat(cache.getLocalCache().getIfPresent(cache.toLocalKey("key")))
        .isSameAs(NullValue.INSTANCE);
    Cache.ValueWrapper wrapper = other.get("key");
    assertThat(wrapper).isNotNull();
    assertThat(wrapper.get()).isNull();
  }

  @Test
  void putIfAbsentNullStoresMarkerWhenNullCachingIsEnabled() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache cache =
        cache("cache", writer, RedisSerializer.json(), breaker("pia-null"), true);
    MultiLevelCache other =
        cache("cache", writer, RedisSerializer.json(), breaker("pia-null-other"), true);

    assertThat(cache.putIfAbsent("key", null)).isNull();

    assertThat(writer.putIfAbsentCalls).hasValue(1);
    assertThat(cache.getLocalCache().getIfPresent(cache.toLocalKey("key")))
        .isSameAs(NullValue.INSTANCE);
    Cache.ValueWrapper wrapper = other.get("key");
    assertThat(wrapper).isNotNull();
    assertThat(wrapper.get()).isNull();
  }

  @Test
  void putIfAbsentTreatsCachedNullAsPresent() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache cache =
        cache("cache", writer, RedisSerializer.json(), breaker("pia-present"), true);
    MultiLevelCache cold =
        cache("cache", writer, RedisSerializer.json(), breaker("pia-present-cold"), true);
    cache.put("key", null);

    Cache.ValueWrapper warm = cache.putIfAbsent("key", "candidate");
    Cache.ValueWrapper remote = cold.putIfAbsent("key", "candidate");

    assertThat(warm).isNotNull();
    assertThat(warm.get()).isNull();
    assertThat(remote).isNotNull();
    assertThat(remote.get()).isNull();
    assertThat(cold.getLocalCache().getIfPresent(cold.toLocalKey("key")))
        .isSameAs(NullValue.INSTANCE);
  }

  @Test
  void evictIfPresentReportsCachedNull() {
    MultiLevelCache cache =
        cache(
            "cache",
            new TestRedisCacheWriter(),
            RedisSerializer.json(),
            breaker("evict-null"),
            true);
    cache.put("key", null);

    assertThat(cache.evictIfPresent("key")).isTrue();
    assertThat(cache.get("key")).isNull();
  }

  @Test
  void typedGetReturnsNullForCachedNull() {
    MultiLevelCache cache =
        cache(
            "cache",
            new TestRedisCacheWriter(),
            RedisSerializer.json(),
            breaker("typed-null"),
            true);
    cache.put("key", null);

    assertThat(cache.get("key", String.class)).isNull();
  }

  @Test
  void nullIsCachedLocallyWhenRedisIsUnavailable() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    writer.failWith(new RedisConnectionFailureException("Redis is unavailable"));
    MultiLevelCache cache =
        cache("cache", writer, RedisSerializer.json(), breaker("null-availability"), true);

    assertThat((Object) cache.get("key", () -> null)).isNull();
    assertThat(cache.getLocalCache().getIfPresent(cache.toLocalKey("key")))
        .isSameAs(NullValue.INSTANCE);
  }

  @Test
  void nullMarkerDoesNotDependOnValueSerializer() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache cache =
        cache("cache", writer, stringSerializer(), breaker("string-null"), true);
    MultiLevelCache other =
        cache("cache", writer, stringSerializer(), breaker("string-null-other"), true);

    cache.put("key", null);

    Cache.ValueWrapper wrapper = other.get("key");
    assertThat(wrapper).isNotNull();
    assertThat(wrapper.get()).isNull();
  }

  @Test
  void invalidatedCachedNullIsReplacedByLaterValue() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache first =
        cache("cache", writer, RedisSerializer.json(), breaker("null-replaced-first"), true);
    MultiLevelCache second =
        cache("cache", writer, RedisSerializer.json(), breaker("null-replaced-second"), true);
    first.put("key", null);

    second.put("key", "created");
    first.invalidateLocalEntry(first.toLocalKey("key"));

    assertThat(first.get("key", () -> "unused")).isEqualTo("created");
  }

  @Test
  void loaderReturningNullStillFailsWhenNullCachingIsDisabled() {
    MultiLevelCache cache =
        cache(
            "cache", new TestRedisCacheWriter(), RedisSerializer.json(), breaker("null-disabled"));

    assertThatThrownBy(() -> cache.get("key", () -> null))
        .isInstanceOf(Cache.ValueRetrievalException.class);
    cache.put("key", "value");
    cache.put("key", null);
    assertThat(cache.get("key")).isNull();
    assertThat((Object) cache.nativeGet("key")).isNull();
  }

  @Test
  void serializerDecodedNullValueIsAMissWhenNullCachingIsDisabled() {
    TestRedisCacheWriter writer = new TestRedisCacheWriter();
    MultiLevelCache cache =
        cache("cache", writer, RedisSerializer.json(), breaker("json-null-value"));
    cache.nativePut("key", NullValue.INSTANCE);

    assertThat(cache.get("key")).isNull();
    assertThat(cache.getLocalCache().getIfPresent(cache.toLocalKey("key"))).isNull();
  }

  private static MultiLevelCache cache(
      String name,
      TestRedisCacheWriter writer,
      RedisSerializer<Object> serializer,
      CircuitBreaker breaker) {
    return cache(name, writer, serializer, breaker, false);
  }

  private static MultiLevelCache cache(
      String name,
      TestRedisCacheWriter writer,
      RedisSerializer<Object> serializer,
      CircuitBreaker breaker,
      boolean cacheNullValues) {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();
    properties.setCacheNullValues(cacheNullValues);
    RedisTemplate<Object, Object> template = mock(RedisTemplate.class);
    doReturn(serializer).when(template).getValueSerializer();
    return new MultiLevelCache(
        name,
        properties,
        writer,
        template,
        Caffeine.newBuilder().maximumSize(100).build(),
        breaker,
        name + "-instance");
  }

  @SuppressWarnings("unchecked")
  private static RedisSerializer<Object> stringSerializer() {
    return (RedisSerializer<Object>) (RedisSerializer<?>) StringRedisSerializer.UTF_8;
  }

  private static CircuitBreaker breaker(String name) {
    return CircuitBreaker.of(
        name,
        CircuitBreakerConfig.custom()
            .minimumNumberOfCalls(1)
            .slidingWindowSize(2)
            .failureRateThreshold(50)
            .slowCallDurationThreshold(Duration.ofSeconds(5))
            .build());
  }

  private static final class EquivalentKey {
    @Override
    public String toString() {
      return "same-key";
    }
  }

  private static final class TrackingReentrantLock extends ReentrantLock {
    private final AtomicInteger lockCalls = new AtomicInteger();
    private final CountDownLatch lockAttempted = new CountDownLatch(1);

    @Override
    public void lock() {
      lockCalls.incrementAndGet();
      lockAttempted.countDown();
      super.lock();
    }

    private void holdByTest() {
      super.lock();
    }

    private void releaseByTest() {
      super.unlock();
    }
  }
}
