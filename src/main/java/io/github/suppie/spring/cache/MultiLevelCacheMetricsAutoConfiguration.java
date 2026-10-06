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

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.cache.metrics.CacheMeterBinderProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Keeps Spring Boot's startup cache-metrics binding from registering multi-level caches a second
 * time. Applies to any {@link MultiLevelCacheManager} bean, including one the application wires
 * itself.
 */
@AutoConfiguration(after = MultiLevelCacheAutoConfiguration.class)
@ConditionalOnClass({MeterBinder.class, CacheMeterBinderProvider.class})
@ConditionalOnBean(MultiLevelCacheManager.class)
public class MultiLevelCacheMetricsAutoConfiguration {

  /**
   * Binds nothing for caches whose manager already registers their meters, and falls back to plain
   * local-tier Caffeine metrics for caches created without a meter registry. Ordered first so
   * Boot's generic Redis provider never binds these caches with a conflicting tag set.
   *
   * @return provider for multi-level cache meters
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public CacheMeterBinderProvider<@NonNull MultiLevelCache>
      multiLevelCacheCacheMeterBinderProvider() {
    return (cache, tags) ->
        cache.getMetrics() == MultiLevelCacheMetrics.NOOP
            ? new CaffeineCacheMetrics<>(cache.getLocalCache(), cache.getName(), tags)
            : registry -> {};
  }
}
