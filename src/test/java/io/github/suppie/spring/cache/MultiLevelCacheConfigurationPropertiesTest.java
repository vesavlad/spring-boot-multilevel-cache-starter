package io.github.suppie.spring.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.suppie.spring.cache.MultiLevelCacheConfigurationProperties.CacheOverrideProperties;
import io.github.suppie.spring.cache.MultiLevelCacheConfigurationProperties.CircuitBreakerProperties;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class MultiLevelCacheConfigurationPropertiesTest {

  @Test
  void derivedCircuitBreakerDefaultsFollowSlowCallDuration() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();
    CircuitBreakerProperties cbp = properties.getCircuitBreaker();

    cbp.setSlowCallDurationThreshold(Duration.ofSeconds(1));

    assertThat(cbp.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(5);
    assertThat(cbp.getMaxWaitDurationInHalfOpenState()).isEqualTo(Duration.ofSeconds(5));
    assertThat(cbp.getSlidingWindowSize()).isEqualTo(10);
    assertThat(cbp.getMinimumNumberOfCalls()).isEqualTo(2);
    assertThat(cbp.getWaitDurationInOpenState()).isEqualTo(Duration.ofSeconds(2));
  }

  @Test
  void circuitBreakerManualOverridesPreserved() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();
    CircuitBreakerProperties cbp = properties.getCircuitBreaker();

    cbp.setSlowCallDurationThreshold(Duration.ofSeconds(2));
    cbp.setPermittedNumberOfCallsInHalfOpenState(3);
    cbp.setMaxWaitDurationInHalfOpenState(Duration.ofSeconds(7));
    cbp.setSlidingWindowSize(30);
    cbp.setMinimumNumberOfCalls(4);
    cbp.setWaitDurationInOpenState(Duration.ofSeconds(9));

    assertThat(cbp.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
    assertThat(cbp.getMaxWaitDurationInHalfOpenState()).isEqualTo(Duration.ofSeconds(7));
    assertThat(cbp.getSlidingWindowSize()).isEqualTo(30);
    assertThat(cbp.getMinimumNumberOfCalls()).isEqualTo(4);
    assertThat(cbp.getWaitDurationInOpenState()).isEqualTo(Duration.ofSeconds(9));
  }

  @Test
  void toRedisCacheConfigurationAppliesConfiguredKeyPrefix() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();
    properties.setUseKeyPrefix(true);
    properties.setKeyPrefix("ml-");

    var configuration = properties.toRedisCacheConfiguration();

    assertThat(configuration.usePrefix()).isTrue();
    assertThat(configuration.getKeyPrefixFor("books")).isEqualTo("ml-books::");
  }

  @Test
  void nullValueCachingIsDisabledByDefault() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();

    assertThat(properties.isCacheNullValues()).isFalse();
  }

  @Test
  void nullValueCachingBindsFromKebabCaseProperty() {
    Binder binder =
        new Binder(
            new MapConfigurationPropertySource(
                Map.of("spring.cache.multilevel.cache-null-values", "true")));

    MultiLevelCacheConfigurationProperties properties =
        binder.bind("spring.cache.multilevel", MultiLevelCacheConfigurationProperties.class).get();

    assertThat(properties.isCacheNullValues()).isTrue();
  }

  @Test
  void forCacheWithoutOverrideReturnsGlobalInstance() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();

    assertThat(properties.forCache("products")).isSameAs(properties);
  }

  @Test
  void forCacheInheritsUnsetFieldsAndDerivesLocalTtlFromOverriddenRedisTtl() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();
    properties.setUseKeyPrefix(true);
    properties.setKeyPrefix("ml-");
    properties.setTopic("custom:topic");
    properties.setCacheNullValues(true);
    properties.getLocal().setMaxSize(123);
    properties.getLocal().setExpiryJitter(10);
    properties.getLocal().setExpirationMode(LocalExpirationMode.AFTER_READ);
    CacheOverrideProperties override = new CacheOverrideProperties();
    override.setTimeToLive(Duration.ofMinutes(30));
    properties.getCaches().put("sourceNodes", override);

    MultiLevelCacheConfigurationProperties resolved = properties.forCache("sourceNodes");

    assertThat(resolved).isNotSameAs(properties);
    assertThat(resolved.getTimeToLive()).isEqualTo(Duration.ofMinutes(30));
    assertThat(resolved.effectiveLocalTimeToLive()).isEqualTo(Duration.ofMinutes(30));
    assertThat(resolved.isCacheNullValues()).isTrue();
    assertThat(resolved.isUseKeyPrefix()).isTrue();
    assertThat(resolved.getKeyPrefix()).isEqualTo("ml-");
    assertThat(resolved.getTopic()).isEqualTo("custom:topic");
    assertThat(resolved.getLocal().getMaxSize()).isEqualTo(123);
    assertThat(resolved.getLocal().getExpiryJitter()).isEqualTo(10);
    assertThat(resolved.getLocal().getExpirationMode()).isEqualTo(LocalExpirationMode.AFTER_READ);
    assertThat(resolved.getCircuitBreaker()).isSameAs(properties.getCircuitBreaker());
    assertThat(resolved.getCaches()).isEmpty();
  }

  @Test
  void forCacheAppliesEveryOverriddenField() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();
    CacheOverrideProperties override = new CacheOverrideProperties();
    override.setTimeToLive(Duration.ofMinutes(180));
    override.setCacheNullValues(true);
    override.getLocal().setMaxSize(250);
    override.getLocal().setTimeToLive(Duration.ofMinutes(5));
    override.getLocal().setExpiryJitter(5);
    override.getLocal().setExpirationMode(LocalExpirationMode.AFTER_UPDATE);
    properties.getCaches().put("deliveryClassesBySubsidiary", override);

    MultiLevelCacheConfigurationProperties resolved =
        properties.forCache("deliveryClassesBySubsidiary");

    assertThat(resolved.getTimeToLive()).isEqualTo(Duration.ofMinutes(180));
    assertThat(resolved.isCacheNullValues()).isTrue();
    assertThat(resolved.getLocal().getMaxSize()).isEqualTo(250);
    assertThat(resolved.getLocal().getTimeToLive()).contains(Duration.ofMinutes(5));
    assertThat(resolved.effectiveLocalTimeToLive()).isEqualTo(Duration.ofMinutes(5));
    assertThat(resolved.getLocal().getExpiryJitter()).isEqualTo(5);
    assertThat(resolved.getLocal().getExpirationMode()).isEqualTo(LocalExpirationMode.AFTER_UPDATE);
  }

  @Test
  void forCachePrefersGlobalLocalTtlOverOverriddenRedisTtl() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();
    properties.getLocal().setTimeToLive(Optional.of(Duration.ofMinutes(2)));
    CacheOverrideProperties override = new CacheOverrideProperties();
    override.setTimeToLive(Duration.ofMinutes(30));
    properties.getCaches().put("prices", override);

    MultiLevelCacheConfigurationProperties resolved = properties.forCache("prices");

    assertThat(resolved.getTimeToLive()).isEqualTo(Duration.ofMinutes(30));
    assertThat(resolved.effectiveLocalTimeToLive()).isEqualTo(Duration.ofMinutes(2));
  }

  @Test
  void forCacheCanDisableNullCachingEnabledGlobally() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();
    properties.setCacheNullValues(true);
    CacheOverrideProperties override = new CacheOverrideProperties();
    override.setCacheNullValues(false);
    properties.getCaches().put("products", override);

    assertThat(properties.forCache("products").isCacheNullValues()).isFalse();
    assertThat(properties.forCache("other").isCacheNullValues()).isTrue();
  }

  @Test
  void forCacheDoesNotMutateGlobalProperties() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();
    CacheOverrideProperties override = new CacheOverrideProperties();
    override.setTimeToLive(Duration.ofMinutes(5));
    override.setCacheNullValues(true);
    override.getLocal().setMaxSize(10);
    override.getLocal().setTimeToLive(Duration.ofMinutes(1));
    override.getLocal().setExpiryJitter(1);
    override.getLocal().setExpirationMode(LocalExpirationMode.AFTER_READ);
    properties.getCaches().put("products", override);

    properties.forCache("products");

    assertThat(properties.getTimeToLive()).isEqualTo(Duration.ofHours(1));
    assertThat(properties.isCacheNullValues()).isFalse();
    assertThat(properties.getLocal().getMaxSize()).isEqualTo(2000);
    assertThat(properties.getLocal().getTimeToLive()).isEmpty();
    assertThat(properties.getLocal().getExpiryJitter()).isEqualTo(50);
    assertThat(properties.getLocal().getExpirationMode())
        .isEqualTo(LocalExpirationMode.AFTER_CREATE);
    assertThat(properties.getCaches()).containsOnlyKeys("products");
  }

  @Test
  void cacheOverridesBindFromCamelCaseAndBracketedNames() {
    Binder binder =
        new Binder(
            new MapConfigurationPropertySource(
                Map.of(
                    "spring.cache.multilevel.caches.sourceNodes.time-to-live", "30m",
                    "spring.cache.multilevel.caches.sourceNodes.cache-null-values", "true",
                    "spring.cache.multilevel.caches.sourceNodes.local.max-size", "100",
                    "spring.cache.multilevel.caches.sourceNodes.local.time-to-live", "5m",
                    "spring.cache.multilevel.caches.sourceNodes.local.expiry-jitter", "5",
                    "spring.cache.multilevel.caches.sourceNodes.local.expiration-mode",
                        "after-read",
                    "spring.cache.multilevel.caches.[user.byId].time-to-live", "10m")));

    MultiLevelCacheConfigurationProperties properties =
        binder.bind("spring.cache.multilevel", MultiLevelCacheConfigurationProperties.class).get();

    assertThat(properties.getCaches()).containsOnlyKeys("sourceNodes", "user.byId");
    CacheOverrideProperties sourceNodes = properties.getCaches().get("sourceNodes");
    assertThat(sourceNodes.getTimeToLive()).isEqualTo(Duration.ofMinutes(30));
    assertThat(sourceNodes.getCacheNullValues()).isTrue();
    assertThat(sourceNodes.getLocal().getMaxSize()).isEqualTo(100);
    assertThat(sourceNodes.getLocal().getTimeToLive()).isEqualTo(Duration.ofMinutes(5));
    assertThat(sourceNodes.getLocal().getExpiryJitter()).isEqualTo(5);
    assertThat(sourceNodes.getLocal().getExpirationMode())
        .isEqualTo(LocalExpirationMode.AFTER_READ);
    CacheOverrideProperties userById = properties.getCaches().get("user.byId");
    assertThat(userById.getTimeToLive()).isEqualTo(Duration.ofMinutes(10));
    assertThat(userById.getCacheNullValues()).isNull();
    assertThat(userById.getLocal().getMaxSize()).isNull();
  }

  @Test
  void cacheOverrideKeysFromEnvironmentVariablesAreLowercased() {
    Binder binder =
        new Binder(
            ConfigurationPropertySources.from(
                new SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    Map.of("SPRING_CACHE_MULTILEVEL_CACHES_SOURCENODES_TIMETOLIVE", "30m"))));

    MultiLevelCacheConfigurationProperties properties =
        binder.bind("spring.cache.multilevel", MultiLevelCacheConfigurationProperties.class).get();

    assertThat(properties.getCaches()).containsOnlyKeys("sourcenodes");
  }

  @Test
  void circuitBreakerIsEnabledByDefault() {
    MultiLevelCacheConfigurationProperties properties =
        new MultiLevelCacheConfigurationProperties();

    assertThat(properties.getCircuitBreaker().isEnabled()).isTrue();
  }
}
