package io.github.suppie.spring.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.suppie.spring.cache.MultiLevelCacheConfigurationProperties.CircuitBreakerProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

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
}
