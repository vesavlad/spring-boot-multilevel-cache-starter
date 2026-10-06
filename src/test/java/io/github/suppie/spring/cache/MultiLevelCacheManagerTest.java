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

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.suppie.spring.cache.MultiLevelCacheConfigurationProperties.CacheOverrideProperties;
import io.github.suppie.spring.cache.MultiLevelCacheManager.RandomizedLocalExpiry;
import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(
    classes = {
      DataRedisAutoConfiguration.class,
      CacheAutoConfiguration.class,
      MultiLevelCacheAutoConfiguration.class
    })
class MultiLevelCacheManagerTest extends AbstractRedisIntegrationTest {
  @Autowired MultiLevelCacheManager cacheManager;
  @Autowired ObjectProvider<@NonNull CacheProperties> cachePropertiesProvider;

  @Autowired
  @Qualifier(MultiLevelCacheAutoConfiguration.CIRCUIT_BREAKER_NAME)
  CircuitBreaker circuitBreaker;

  @Autowired
  @Qualifier(MultiLevelCacheAutoConfiguration.CACHE_REDIS_TEMPLATE_NAME)
  RedisTemplate<Object, Object> multiLevelCacheRedisTemplate;

  @Test
  void cacheNamesTest() {
    final String key = "cacheNamesTest";

    Assertions.assertDoesNotThrow(
        () -> cacheManager.getCache(key), "Cache should be automatically created upon request");
    Assertions.assertTrue(
        cacheManager.getCacheNames().contains(key), "Cache name must be accessible");
  }

  @Test
  void cachesReceiveTheirOwnOverrides() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();
    CacheOverrideProperties products = new CacheOverrideProperties();
    products.setTimeToLive(Duration.ofMinutes(30));
    products.getLocal().setMaxSize(5000);
    products.getLocal().setTimeToLive(Duration.ofMinutes(5));
    properties.getCaches().put("products", products);
    MultiLevelCacheManager manager = newManager(properties);

    MultiLevelCache productsCache = (MultiLevelCache) manager.getCache("products");
    MultiLevelCache otherCache = (MultiLevelCache) manager.getCache("other");

    Assertions.assertNotNull(productsCache);
    Assertions.assertNotNull(otherCache);
    Assertions.assertEquals(
        5000L, productsCache.getLocalCache().policy().eviction().orElseThrow().getMaximum());
    Assertions.assertEquals(
        2000L, otherCache.getLocalCache().policy().eviction().orElseThrow().getMaximum());
    Assertions.assertEquals(Duration.ofMinutes(30), productsCache.properties.getTimeToLive());
    Assertions.assertEquals(
        Duration.ofMinutes(5), productsCache.properties.effectiveLocalTimeToLive());
    Assertions.assertEquals(
        Duration.ofMinutes(30),
        productsCache.getCacheConfiguration().getTtlFunction().getTimeToLive("k", "v"));
    Assertions.assertSame(properties, otherCache.properties);
  }

  @Test
  void invalidOverrideFailsManagerConstructionNamingTheCache() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();
    CacheOverrideProperties broken = new CacheOverrideProperties();
    broken.getLocal().setExpiryJitter(150);
    properties.getCaches().put("broken", broken);

    IllegalArgumentException exception =
        Assertions.assertThrows(IllegalArgumentException.class, () -> newManager(properties));

    Assertions.assertEquals(
        "Invalid configuration for cache 'broken': Expiry jitter must not exceed 100 percents",
        exception.getMessage());
  }

  private MultiLevelCacheManager newManager(MultiLevelCacheConfigurationProperties properties) {
    return new MultiLevelCacheManager(
        cachePropertiesProvider, properties, multiLevelCacheRedisTemplate, circuitBreaker);
  }

  @Nested
  class RandomizedLocalExpiryTest {
    @Test
    void expirationUsesResolvedLocalTimeToLiveOfOverride() {
      MultiLevelCacheConfigurationProperties properties =
          new MultiLevelCacheConfigurationProperties();
      CacheOverrideProperties override = new CacheOverrideProperties();
      override.getLocal().setTimeToLive(Duration.ofSeconds(2));
      override.getLocal().setExpiryJitter(0);
      properties.getCaches().put("c", override);

      RandomizedLocalExpiry expiry = new RandomizedLocalExpiry(properties.forCache("c"));

      Assertions.assertEquals(
          Duration.ofSeconds(1).toNanos(),
          expiry.expireAfterCreate("key", "value", 0),
          "Zero jitter must expire at half of the overridden local TTL");
    }

    @Test
    void negativeTimeToLive() {
      MultiLevelCacheConfigurationProperties properties =
          new MultiLevelCacheConfigurationProperties();
      properties.setTimeToLive(Duration.ofSeconds(1).negated());

      Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> new RandomizedLocalExpiry(properties),
          "Negative TTL must throw an exception");
    }

    @Test
    void zeroTimeToLive() {
      MultiLevelCacheConfigurationProperties properties =
          new MultiLevelCacheConfigurationProperties();
      properties.setTimeToLive(Duration.ZERO);

      Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> new RandomizedLocalExpiry(properties),
          "Zero TTL must throw an exception");
    }

    @Test
    void negativeExpiryJitter() {
      MultiLevelCacheConfigurationProperties properties =
          new MultiLevelCacheConfigurationProperties();
      properties.getLocal().setExpiryJitter(-1);

      Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> new RandomizedLocalExpiry(properties),
          "Negative expiry jitter must throw an exception");
    }

    @Test
    void tooBigExpiryJitter() {
      MultiLevelCacheConfigurationProperties properties =
          new MultiLevelCacheConfigurationProperties();
      properties.getLocal().setExpiryJitter(200);

      Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> new RandomizedLocalExpiry(properties),
          "Too big expiry jitter must throw an exception");
    }

    @Test
    void negativeLocalTimeToLive() {
      MultiLevelCacheConfigurationProperties properties =
          new MultiLevelCacheConfigurationProperties();
      properties.getLocal().setTimeToLive(Optional.of(Duration.ofSeconds(1).negated()));

      Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> new RandomizedLocalExpiry(properties),
          "Negative TTL must throw an exception");
    }

    @Test
    void zeroLocalTimeToLive() {
      MultiLevelCacheConfigurationProperties properties =
          new MultiLevelCacheConfigurationProperties();
      properties.getLocal().setTimeToLive(Optional.of(Duration.ZERO));

      Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> new RandomizedLocalExpiry(properties),
          "Zero TTL must throw an exception");
    }

    @Test
    void expirationWithinConfiguredJitterRange() {
      MultiLevelCacheConfigurationProperties properties =
          new MultiLevelCacheConfigurationProperties();
      properties.setTimeToLive(Duration.ofSeconds(10));
      properties.getLocal().setExpiryJitter(20);

      RandomizedLocalExpiry expiry = new RandomizedLocalExpiry(properties);
      Duration ttl = Duration.ofSeconds(10);
      double baseMultiplier = 0.5d;
      double jitterFraction = properties.getLocal().getExpiryJitter() / 100d;
      long minNanos = (long) (ttl.toNanos() * baseMultiplier * (1 - jitterFraction));
      long maxNanos = (long) (ttl.toNanos() * baseMultiplier * (1 + jitterFraction));

      for (int i = 0; i < 100; i++) {
        long computedNanos = expiry.expireAfterCreate("key-" + i, "value", 0);
        Assertions.assertTrue(
            computedNanos >= minNanos && computedNanos <= maxNanos,
            "Computed expiration must respect jitter bounds");
      }
    }
  }
}
