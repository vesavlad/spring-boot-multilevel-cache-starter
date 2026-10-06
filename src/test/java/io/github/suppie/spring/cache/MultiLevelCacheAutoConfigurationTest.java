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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.cache.CacheType;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheProperties;
import org.springframework.boot.cache.autoconfigure.metrics.CacheMetricsAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@SuppressWarnings("unchecked")
@ExtendWith(OutputCaptureExtension.class)
class MultiLevelCacheAutoConfigurationTest extends AbstractRedisIntegrationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  MultiLevelCacheAutoConfiguration.class,
                  MultiLevelCacheMetricsAutoConfiguration.class,
                  DataRedisAutoConfiguration.class,
                  CacheAutoConfiguration.class));

  @Test
  void instantiationTest() {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .run(
            context -> {
              Assertions.assertThat(context)
                  .hasBean(MultiLevelCacheAutoConfiguration.CACHE_REDIS_TEMPLATE_NAME);
              Assertions.assertThat(context).hasSingleBean(MultiLevelCacheManager.class);
              Assertions.assertThat(context).hasSingleBean(RedisMessageListenerContainer.class);
              Assertions.assertThat(context)
                  .hasBean(
                      MultiLevelCacheAutoConfiguration.CACHE_INVALIDATION_MESSAGE_LISTENER_NAME);
              Assertions.assertThat(context)
                  .hasBean(
                      MultiLevelCacheAutoConfiguration
                          .CACHE_INVALIDATION_MESSAGE_LISTENER_REGISTRAR_NAME);
            });
  }

  @Test
  void instantiationTestWithCacheNames() {
    final String cache1 = "cache1";
    final String cache2 = "cache2";

    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .withPropertyValues("spring.cache.cache-names=" + cache1)
        .run(
            context -> {
              Assertions.assertThat(context)
                  .hasBean(MultiLevelCacheAutoConfiguration.CACHE_REDIS_TEMPLATE_NAME);
              Assertions.assertThat(context).hasSingleBean(MultiLevelCacheManager.class);
              Assertions.assertThat(context).hasSingleBean(RedisMessageListenerContainer.class);
              Assertions.assertThat(context)
                  .hasBean(
                      MultiLevelCacheAutoConfiguration.CACHE_INVALIDATION_MESSAGE_LISTENER_NAME);
              Assertions.assertThat(context)
                  .hasBean(
                      MultiLevelCacheAutoConfiguration
                          .CACHE_INVALIDATION_MESSAGE_LISTENER_REGISTRAR_NAME);

              MultiLevelCacheManager cacheManager = context.getBean(MultiLevelCacheManager.class);
              Assertions.assertThat(cacheManager.getCacheNames()).contains(cache1);
              Assertions.assertThat(cacheManager.getCacheNames()).doesNotContain(cache2);

              Assertions.assertThat(cacheManager.getCache(cache2)).isNull();
            });
  }

  @ParameterizedTest
  @MethodSource("incorrectCacheTypes")
  void instantiationTestWithDifferentCacheTypes(CacheType cacheType) {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + cacheType.name().toLowerCase())
        .run(
            context -> {
              Assertions.assertThat(context)
                  .doesNotHaveBean(MultiLevelCacheAutoConfiguration.CACHE_REDIS_TEMPLATE_NAME);
              Assertions.assertThat(context).doesNotHaveBean(MultiLevelCacheManager.class);
              Assertions.assertThat(context)
                  .doesNotHaveBean(
                      MultiLevelCacheAutoConfiguration.CACHE_INVALIDATION_MESSAGE_LISTENER_NAME);
              Assertions.assertThat(context)
                  .doesNotHaveBean(
                      MultiLevelCacheAutoConfiguration
                          .CACHE_INVALIDATION_MESSAGE_LISTENER_REGISTRAR_NAME);
            });
  }

  @ParameterizedTest
  @MethodSource("localExpirationModes")
  void instantiationTestWithDifferentLocalExpirationModes(
      String mode, LocalExpirationMode expected) {
    ApplicationContextRunner applicationContextRunner =
        this.runner
            .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
            .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
            .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase());

    if (mode != null) {
      applicationContextRunner =
          applicationContextRunner.withPropertyValues(
              "spring.cache.multilevel.local.expiration-mode=" + mode);
    }

    applicationContextRunner.run(
        context -> {
          MultiLevelCacheManager cacheManager = context.getBean(MultiLevelCacheManager.class);
          Assertions.assertThat(cacheManager.getProperties().getLocal().getExpirationMode())
              .isEqualTo(expected);
        });
  }

  static Stream<Arguments> incorrectCacheTypes() {
    return Arrays.stream(CacheType.values())
        .filter(cacheType -> !CacheType.REDIS.equals(cacheType))
        .map(Arguments::of);
  }

  static Stream<Arguments> localExpirationModes() {
    return Stream.of(
        Arguments.of(null, LocalExpirationMode.AFTER_CREATE),
        Arguments.of("after-create", LocalExpirationMode.AFTER_CREATE),
        Arguments.of("AFTER_CREATE", LocalExpirationMode.AFTER_CREATE),
        Arguments.of("after-update", LocalExpirationMode.AFTER_UPDATE),
        Arguments.of("AFTER_UPDATE", LocalExpirationMode.AFTER_UPDATE),
        Arguments.of("after-read", LocalExpirationMode.AFTER_READ),
        Arguments.of("AFTER_READ", LocalExpirationMode.AFTER_READ));
  }

  @Test
  void circuitBreakerWarnsWhenWaitDurationTooHigh(CapturedOutput output) {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .withPropertyValues("spring.cache.multilevel.time-to-live=10s")
        .withPropertyValues("spring.cache.multilevel.local.expiry-jitter=80")
        .withPropertyValues(
            "spring.cache.multilevel.circuit-breaker.wait-duration-in-open-state=3s")
        .run(context -> Assertions.assertThat(context).hasSingleBean(MultiLevelCacheManager.class));

    Assertions.assertThat(output)
        .contains(
            "Cache circuit breaker wait duration in open state PT3S is more than recommended value"
                + " of PT1S");
  }

  @Test
  void useKeyPrefixWithoutPrefixFailsFast() {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .withPropertyValues("spring.cache.cache-names=test")
        .withPropertyValues("spring.cache.multilevel.use-key-prefix=true")
        .withPropertyValues("spring.cache.multilevel.key-prefix=")
        .run(
            context -> {
              Throwable failure = context.getStartupFailure();
              Assertions.assertThat(failure)
                  .isNotNull()
                  .hasMessageContaining("spring.cache.multilevel.key-prefix");
            });
  }

  @Test
  void redisTemplateUsesJsonValueSerializerByDefault() {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .run(
            context -> {
              RedisTemplate<Object, Object> template =
                  context.getBean(
                      MultiLevelCacheAutoConfiguration.CACHE_REDIS_TEMPLATE_NAME,
                      RedisTemplate.class);
              Assertions.assertThat(template.getValueSerializer())
                  .isInstanceOf(GenericJacksonJsonRedisSerializer.class);
            });
  }

  @Test
  void redisTemplateUsesCustomValueSerializerWhenProvided() {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .withUserConfiguration(CustomSerializerConfiguration.class)
        .run(
            context -> {
              RedisTemplate<Object, Object> template =
                  context.getBean(
                      MultiLevelCacheAutoConfiguration.CACHE_REDIS_TEMPLATE_NAME,
                      RedisTemplate.class);
              Assertions.assertThat(template.getValueSerializer())
                  .isInstanceOf(StringRedisSerializer.class);
            });
  }

  static class CustomSerializerConfiguration {
    @Bean
    RedisSerializer<@NonNull Object> legacyCustomValueSerializer() {
      return (RedisSerializer<@NonNull Object>) (RedisSerializer<?>) new StringRedisSerializer();
    }
  }

  @Test
  void overrideForCacheOutsideCacheNamesLogsWarning(CapturedOutput output) {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .withPropertyValues("spring.cache.cache-names=products")
        .withPropertyValues("spring.cache.multilevel.caches.products.local.max-size=5000")
        .withPropertyValues("spring.cache.multilevel.caches.sourceNodes.local.max-size=100")
        .run(
            context -> {
              MultiLevelCacheManager cacheManager = context.getBean(MultiLevelCacheManager.class);
              MultiLevelCache products = (MultiLevelCache) cacheManager.getCache("products");
              Assertions.assertThat(products).isNotNull();
              Assertions.assertThat(
                      products.getLocalCache().policy().eviction().orElseThrow().getMaximum())
                  .isEqualTo(5000L);
              Assertions.assertThat(cacheManager.getProperties().getCaches())
                  .containsKey("sourceNodes");
            });

    Assertions.assertThat(output)
        .contains(
            "Cache override 'spring.cache.multilevel.caches.sourceNodes' will never apply because"
                + " 'sourceNodes' is not listed in 'spring.cache.cache-names'")
        .doesNotContain("caches.products' will never apply");
  }

  @Test
  void overrideWithoutCacheNamesDoesNotWarn(CapturedOutput output) {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .withPropertyValues("spring.cache.multilevel.caches.sourceNodes.local.max-size=100")
        .run(context -> Assertions.assertThat(context).hasSingleBean(MultiLevelCacheManager.class));

    Assertions.assertThat(output).doesNotContain("will never apply");
  }

  @Test
  void invalidOverrideOnEagerCacheFailsStartup() {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .withPropertyValues("spring.cache.cache-names=broken")
        .withPropertyValues("spring.cache.multilevel.caches.broken.local.expiry-jitter=150")
        .run(
            context -> {
              Assertions.assertThat(context).hasFailed();
              Assertions.assertThat(context.getStartupFailure())
                  .hasStackTraceContaining(
                      "Invalid configuration for cache 'broken': Expiry jitter must not exceed 100"
                          + " percents");
            });
  }

  @Test
  void circuitBreakerWarnsForCacheWhoseOverrideExpiresTooSoon(CapturedOutput output) {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .withPropertyValues(
            "spring.cache.multilevel.circuit-breaker.wait-duration-in-open-state=3s")
        .withPropertyValues("spring.cache.multilevel.caches.prices.local.time-to-live=4s")
        .withPropertyValues("spring.cache.multilevel.caches.prices.local.expiry-jitter=0")
        .run(context -> Assertions.assertThat(context).hasSingleBean(MultiLevelCacheManager.class));

    Assertions.assertThat(output)
        .contains(
            "Cache circuit breaker wait duration in open state PT3S is more than recommended value"
                + " of PT2S for cache 'prices'")
        .doesNotContain("recommended value of PT15M");
  }

  @Test
  void invalidOverrideOnLazyCacheFailsStartup() {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .withPropertyValues("spring.cache.multilevel.caches.broken.local.max-size=-1")
        .run(
            context -> {
              Assertions.assertThat(context).hasFailed();
              Assertions.assertThat(context.getStartupFailure())
                  .hasStackTraceContaining("Invalid configuration for cache 'broken'");
            });
  }

  @Test
  void invalidOverrideOutsideCacheNamesOnlyWarns(CapturedOutput output) {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .withPropertyValues("spring.cache.cache-names=products")
        .withPropertyValues("spring.cache.multilevel.caches.broken.local.expiry-jitter=150")
        .run(context -> Assertions.assertThat(context).hasSingleBean(MultiLevelCacheManager.class));

    Assertions.assertThat(output)
        .contains("Cache override 'spring.cache.multilevel.caches.broken' will never apply");
  }

  @Test
  void circuitBreakerIsClosedByDefault() {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .run(
            context ->
                Assertions.assertThat(
                        context
                            .getBean(
                                MultiLevelCacheAutoConfiguration.CIRCUIT_BREAKER_NAME,
                                CircuitBreaker.class)
                            .getState())
                    .isEqualTo(CircuitBreaker.State.CLOSED));
  }

  @Test
  void circuitBreakerCanBeDisabled(CapturedOutput output) {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .withPropertyValues("spring.cache.multilevel.circuit-breaker.enabled=false")
        .withPropertyValues("spring.cache.multilevel.time-to-live=10s")
        .withPropertyValues("spring.cache.multilevel.local.expiry-jitter=80")
        .withPropertyValues(
            "spring.cache.multilevel.circuit-breaker.wait-duration-in-open-state=3s")
        .run(
            context ->
                Assertions.assertThat(
                        context
                            .getBean(
                                MultiLevelCacheAutoConfiguration.CIRCUIT_BREAKER_NAME,
                                CircuitBreaker.class)
                            .getState())
                    .isEqualTo(CircuitBreaker.State.DISABLED));

    Assertions.assertThat(output).doesNotContain("Cache circuit breaker wait duration");
  }

  @Test
  void breakerAndEagerCachesAreMeteredWithoutBootNameTag() {
    runner
        .withConfiguration(AutoConfigurations.of(CacheMetricsAutoConfiguration.class))
        .withBean(SimpleMeterRegistry.class)
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .withPropertyValues("spring.cache.cache-names=eager")
        .run(
            context -> {
              MeterRegistry registry = context.getBean(MeterRegistry.class);

              Assertions.assertThat(
                      registry
                          .find("resilience4j.circuitbreaker.state")
                          .tag("name", MultiLevelCacheAutoConfiguration.CIRCUIT_BREAKER_NAME)
                          .gauges())
                  .isNotEmpty();
              Assertions.assertThat(
                      registry.find("cache.size").tags("cache", "eager", "tier", "local").gauge())
                  .isNotNull();
              Assertions.assertThat(registry.getMeters())
                  .noneMatch(
                      meter ->
                          meter.getId().getName().startsWith("cache.")
                              && meter.getId().getTag("name") != null);
            });
  }

  @Test
  void lazilyCreatedCacheIsMetered() {
    runner
        .withConfiguration(AutoConfigurations.of(CacheMetricsAutoConfiguration.class))
        .withBean(SimpleMeterRegistry.class)
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .run(
            context -> {
              MeterRegistry registry = context.getBean(MeterRegistry.class);
              MultiLevelCacheManager cacheManager = context.getBean(MultiLevelCacheManager.class);

              Assertions.assertThat(cacheManager.getCache("lazy").get("metrics-missing-key"))
                  .isNull();

              Assertions.assertThat(
                      registry
                          .get("cache.multilevel.gets")
                          .tags("cache", "lazy", "result", "miss")
                          .counter()
                          .count())
                  .isEqualTo(1d);
            });
  }

  @Test
  void userSuppliedBreakerIsNotMetered() {
    runner
        .withBean(SimpleMeterRegistry.class)
        .withBean(
            MultiLevelCacheAutoConfiguration.CIRCUIT_BREAKER_NAME,
            CircuitBreaker.class,
            () -> CircuitBreaker.ofDefaults("user"))
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .run(
            context ->
                Assertions.assertThat(
                        context
                            .getBean(MeterRegistry.class)
                            .find("resilience4j.circuitbreaker.state")
                            .gauges())
                    .isEmpty());
  }

  @Test
  void cachesWorkWithoutMeterRegistry() {
    runner
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase())
        .run(
            context -> {
              Assertions.assertThat(context).doesNotHaveBean(MeterRegistry.class);
              MultiLevelCacheManager cacheManager = context.getBean(MultiLevelCacheManager.class);
              var cache = cacheManager.getCache("no-registry");
              cache.put("key", "value");
              Assertions.assertThat(cache.get("key").get()).isEqualTo("value");
              cache.evict("key");
            });
  }

  private ApplicationContextRunner handWiredRunner() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                MultiLevelCacheAutoConfiguration.class,
                MultiLevelCacheMetricsAutoConfiguration.class,
                DataRedisAutoConfiguration.class,
                CacheAutoConfiguration.class,
                CacheMetricsAutoConfiguration.class))
        .withBean(SimpleMeterRegistry.class)
        .withPropertyValues("spring.data.redis.host=" + System.getProperty("HOST"))
        .withPropertyValues("spring.data.redis.port=" + System.getProperty("PORT"))
        .withPropertyValues("spring.cache.type=" + CacheType.REDIS.name().toLowerCase());
  }

  @Test
  void handWiredManagerWithRegistryIsNotBoundTwiceByBoot() {
    handWiredRunner()
        .withUserConfiguration(HandWiredMeteredManagerConfiguration.class)
        .run(
            context -> {
              Assertions.assertThat(context).hasSingleBean(MultiLevelCacheManager.class);
              MeterRegistry registry = context.getBean(MeterRegistry.class);
              Assertions.assertThat(
                      registry.find("cache.size").tags("cache", "eager", "tier", "local").gauge())
                  .isNotNull();
              Assertions.assertThat(registry.getMeters())
                  .noneMatch(
                      meter ->
                          meter.getId().getName().startsWith("cache.")
                              && meter.getId().getTag("name") != null);
            });
  }

  @Test
  void handWiredManagerWithoutRegistryKeepsBootBindingOfLocalTier() {
    handWiredRunner()
        .withUserConfiguration(HandWiredUnmeteredManagerConfiguration.class)
        .run(
            context -> {
              MeterRegistry registry = context.getBean(MeterRegistry.class);
              Assertions.assertThat(
                      registry.find("cache.size").tags("cache", "eager", "name", "eager").gauge())
                  .isNotNull();
            });
  }

  private static MultiLevelCacheManager handWiredManager(
      ObjectProvider<@NonNull CacheProperties> cacheProperties,
      RedisConnectionFactory connectionFactory,
      MeterRegistry meterRegistry) {
    RedisTemplate<Object, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(RedisSerializer.json());
    template.afterPropertiesSet();
    MultiLevelCacheManager manager =
        new MultiLevelCacheManager(
            cacheProperties,
            new MultiLevelCacheConfigurationProperties(),
            template,
            CircuitBreaker.ofDefaults("hand-wired"),
            meterRegistry);
    manager.getCache("eager");
    return manager;
  }

  @Configuration(proxyBeanMethods = false)
  static class HandWiredMeteredManagerConfiguration {
    @Bean
    MultiLevelCacheManager cacheManager(
        ObjectProvider<@NonNull CacheProperties> cacheProperties,
        RedisConnectionFactory connectionFactory,
        MeterRegistry meterRegistry) {
      return handWiredManager(cacheProperties, connectionFactory, meterRegistry);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class HandWiredUnmeteredManagerConfiguration {
    @Bean
    MultiLevelCacheManager cacheManager(
        ObjectProvider<@NonNull CacheProperties> cacheProperties,
        RedisConnectionFactory connectionFactory) {
      return handWiredManager(cacheProperties, connectionFactory, null);
    }
  }
}
