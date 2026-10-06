# Spring Boot multi-level cache starter

Opinionated multi-level caching for [Spring Boot](https://spring.io/projects/spring-boot) that
combines [Redis](https://redis.io/) for the distributed tier and [Caffeine](https://github.com/ben-manes/caffeine) for
the in-memory tier, guarded by a Resilience4j circuit breaker.

### Highlights

- **Drop-in starter** – activates automatically when `spring.cache.type=redis`
- **Aggressive hot-path focus** – randomized local TTL keeps Redis warm while preventing stampedes
- **Graceful degradation** – circuit breaker keeps serving from Caffeine if Redis is slow or down
- **Batteries included** – curated defaults so you only tweak what matters

[![Build status](https://github.com/SuppieRK/spring-boot-multilevel-cache-starter/actions/workflows/build.yml/badge.svg)](https://github.com/SuppieRK/spring-boot-multilevel-cache-starter/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.suppierk/spring-boot-multilevel-cache-starter.svg)](https://search.maven.org/artifact/io.github.suppierk/spring-boot-multilevel-cache-starter)
[![Javadoc](https://javadoc.io/badge2/io.github.suppierk/spring-boot-multilevel-cache-starter/javadoc.svg)](https://javadoc.io/doc/io.github.suppierk/spring-boot-multilevel-cache-starter)
[![SonarCloud Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=SuppieRK_spring-boot-multilevel-cache-starter&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=SuppieRK_spring-boot-multilevel-cache-starter)
[![SonarCloud Coverage](https://sonarcloud.io/api/project_badges/measure?project=SuppieRK_spring-boot-multilevel-cache-starter&metric=coverage)](https://sonarcloud.io/summary/new_code?id=SuppieRK_spring-boot-multilevel-cache-starter)
[![SonarCloud Maintainability](https://sonarcloud.io/api/project_badges/measure?project=SuppieRK_spring-boot-multilevel-cache-starter&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=SuppieRK_spring-boot-multilevel-cache-starter)
[![FOSSA Status](https://app.fossa.com/api/projects/git%2Bgithub.com%2FSuppieRK%2Fspring-boot-multilevel-cache-starter.svg?type=shield)](https://app.fossa.com/projects/git%2Bgithub.com%2FSuppieRK%2Fspring-boot-multilevel-cache-starter?ref=badge_shield)

## Usage

### Maven

```xml

<dependency>
    <groupId>io.github.suppierk</groupId>
    <artifactId>spring-boot-multilevel-cache-starter</artifactId>
    <version>4.1.1.1</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.github.suppierk:spring-boot-multilevel-cache-starter:4.1.1.1'
```

### Examples

- `examples/basic-demo` — minimal REST service demonstrating `@Cacheable` with the starter. Clone the repo, start Redis via `docker compose up -d` inside the example directory, then run `./gradlew :examples:basic-demo:bootRun` from the project root.

## Use cases

### Cache behavior

- Caffeine is always checked first. An L1 hit is returned without calling Redis or the circuit
  breaker.
- Redis is the shared L2 fallback after an L1 miss. A Redis hit warms L1.
- On a cold `putIfAbsent`, Redis coordinates concurrent connected instances. If Redis is
  unavailable, the operation remains atomic only inside the current application instance.
- Connection failures, timeouts, and an open circuit breaker fall back to local caching. Cache key
  conversion, serialization, validation, and programming errors are propagated to the caller.
- Null values are not cached by default. `put(key, null)` and `putIfAbsent(key, null)` evict the
  key, and a loader returning null fails with `Cache.ValueRetrievalException`.
- Set `spring.cache.multilevel.cache-null-values=true` for negative caching: `null` results are
  cached in both tiers with the regular TTL, so a missing entity is loaded once per cluster until
  the cached null expires or is evicted. `put(key, null)` stores the null, a loader may return
  null, and a cached null counts as present for `putIfAbsent`.
- Enable `cache-null-values` only after every instance sharing the Redis keyspace runs a version
  with null-caching support. Older instances fail to deserialize cached nulls. Instances with the
  flag disabled treat cached nulls as misses, so disabling it again is safe.
- Redis Pub/Sub invalidation uses its own stable v0 JSON codec, independently of the configured
  cache-value serializer.

Spring's asynchronous `Cache.retrieve(...)` methods are not yet multilevel-aware. Applications
that require L1-first behavior should use the synchronous cache methods until async support is
implemented.

### Invalidation message compatibility

Invalidation messaging will move to the stable JSON format over several releases so rolling
upgrades do not silently leave stale L1 entries:

1. Version `4.1.1.1` publishes both the stable v0 JSON message and, when different, the configured
   legacy representation. It accepts both formats. This compatibility release allows every
   application instance to learn the stable format while older instances still receive messages
   they can decode.
2. A later release will publish only the stable JSON message while continuing to accept both stable
   and legacy messages. Before adopting that release, complete a rollout through `4.1.1.1` (or
   another dual-publication release) on every instance that shares the invalidation topic.
3. Legacy message decoding will be removed only in a subsequent release after the stable-only
   publication transition has had a full compatibility window.

Cache-value serialization is independent of this transition and remains controlled by the
configured Redis serializer.

### Suitable for

- Microservices working with immutable cached entities under low latency requirements
    - The goal is to not only reduce the number of calls to external service but also reduce the number of calls to
      Redis

### Not a good fit for

- Mutable cached entities
- Entities with short time to live (< 5 minutes)
- Cases when entities in local cache **must** outlive entities in distributed cache
    - Consider using only local cache instead
- Cases when all calls to Redis must be synchronized with distributed locks

## Ideas

- Use well-known Spring primitives for implementation
- Microservices environment needs to fit the requirement of fault tolerance:
    - Redis calls covered by [Resilience4j Circuit Breaker](https://resilience4j.readme.io/docs/circuitbreaker) which
      allows falling back to use local cache at the cost of increased latency and more calls to external services.
- Redis TTL behaves similar to `expireAfterWrite` in Caffeine which allows us to set randomized expiry time for local
  cache:
    - This is useful to ensure that local cache entries will expire earlier for a higher chance to hit Redis instead of
      performing external call.
    - This also implicitly reduces the load on the Redis by spreading calls to it over time.
    - In the case of Redis connection errors, randomized expiry and Circuit Breaker will help to
      mitigate [thundering herd problem](https://en.wikipedia.org/wiki/Thundering_herd_problem).
- Expiry randomization follows the rule: `(time-to-live / 2) * (1 ± ((expiry-jitter / 100) * RNG(0, 1)))`, for example:
    - If `spring.cache.multilevel.time-to-live` is `1h`
    - And `spring.cache.multilevel.local.expiry-jitter` is `50` (percents)
    - Then entries in local cache will expire in approximately `15-45m`:

```
(1h / 2) * (1 ± ((50 / 100) * RNG(0, 1))) ->
30m * (1 ± MAXRNG(0.5)) ->
30m * RANGE(0.5, 1.5) ->
15-45m
```

## Configuration options

| Property                                                      | Default                  | Notes                                                                                               |
|---------------------------------------------------------------|--------------------------|-----------------------------------------------------------------------------------------------------|
| `spring.cache.multilevel.time-to-live`                        | `1h`                     | TTL applied to Redis entries; local cache derives its randomized expiry from here unless overridden |
| `spring.cache.multilevel.use-key-prefix`                      | `false`                  | Enables `key-prefix`; set to `true` only when you supply a non-empty prefix                         |
| `spring.cache.multilevel.key-prefix`                          | `""`                     | Optional Redis key prefix                                                                           |
| `spring.cache.multilevel.topic`                               | `cache:multilevel:topic` | Redis Pub/Sub channel used to broadcast evictions                                                   |
| `spring.cache.multilevel.cache-null-values`                   | `false`                  | Caches `null` results in both tiers; see the rollout note under "Cache behavior"                    |
| `spring.cache.multilevel.local.max-size`                      | `2000`                   | Maximum number of entries retained in Caffeine                                                      |
| `spring.cache.multilevel.local.expiry-jitter`                 | `50`                     | Percentage used to randomize the local TTL                                                          |
| `spring.cache.multilevel.local.expiration-mode`               | `after-create`           | One of `after-create`, `after-update`, `after-read`                                                 |
| `spring.cache.multilevel.local.time-to-live`                  | empty                    | Optional dedicated TTL for the local cache                                                          |
| `spring.cache.multilevel.caches.<name>.time-to-live`          | global value             | Redis TTL for cache `<name>`; see "Per-cache overrides"                                             |
| `spring.cache.multilevel.caches.<name>.cache-null-values`     | global value             | Null caching for cache `<name>`                                                                     |
| `spring.cache.multilevel.caches.<name>.local.max-size`        | global value             | Caffeine maximum size for cache `<name>`                                                            |
| `spring.cache.multilevel.caches.<name>.local.time-to-live`    | see below                | Local TTL for cache `<name>`                                                                        |
| `spring.cache.multilevel.caches.<name>.local.expiry-jitter`   | global value             | Local expiry jitter for cache `<name>`                                                              |
| `spring.cache.multilevel.caches.<name>.local.expiration-mode` | global value             | Local expiration mode for cache `<name>`                                                            |
| `spring.cache.multilevel.circuit-breaker.enabled`             | `true`                   | `false` keeps the breaker permanently closed; see note below                                        |
| `spring.cache.multilevel.circuit-breaker.*`                   | see YAML                 | Passed directly to Resilience4j’s circuit breaker builder                                           |

With `circuit-breaker.enabled: false` the breaker never opens. Redis outages are still tolerated:
each cache operation tries Redis, and after that call fails it falls back to the local tier. The
difference is that every operation waits for the Redis client timeout during an outage instead of
skipping Redis immediately, so size the client timeout accordingly.

### Per-cache overrides

Each cache can override `time-to-live`, `cache-null-values` and the `local.*` settings under
`spring.cache.multilevel.caches.<name>`. Anything not set inherits the global value. `key-prefix`,
`topic` and `circuit-breaker` are always global.

```yaml
spring:
  cache:
    type: redis
    multilevel:
      time-to-live: 1h
      local:
        max-size: 2000
      caches:
        products:
          time-to-live: 60m
          local:
            max-size: 5000
            time-to-live: 5m
            expiry-jitter: 5
        sourceNodes:
          time-to-live: 30m
          cache-null-values: true
          local:
            max-size: 100
```

- A cache's local TTL is the first value set among `caches.<name>.local.time-to-live`, the global
  `local.time-to-live`, and the cache's own `time-to-live`.
- Overrides do not create caches. When `spring.cache.cache-names` is set, an override for a name not
  in that list never applies and is logged as a warning at startup.
- Every override that can apply is validated at startup; an invalid value (for example
  `local.expiry-jitter: 150`) fails with `Invalid configuration for cache '<name>': ...`.
- Cache names are matched exactly. YAML keys keep their case (`sourceNodes`); names with dots or
  other special characters need brackets (`"[user.byId]"`). Keys set through environment variables
  are lowercased by Spring Boot (`SPRING_CACHE_MULTILEVEL_CACHES_SOURCENODES_TIMETOLIVE` configures
  `sourcenodes`), so use YAML or properties files for camelCase names.

## Default configuration

```yaml
spring:
  data:
    redis:
      host: ${HOST:localhost}
      port: ${PORT:6379}
  cache:
    type: redis

    # These properties are custom
    multilevel:
      # Redis properties
      time-to-live: 1h
      use-key-prefix: false
      key-prefix: ""
      topic: "cache:multilevel:topic"
      cache-null-values: false
      # Local Caffeine cache properties
      local:
        max-size: 2000
        expiry-jitter: 50
        expiration-mode: after-create
        # other valid values for expiration-mode: after-update, after-read
      # Resilience4j Circuit Breaker properties for Redis
      circuit-breaker:
        enabled: true
        failure-rate-threshold: 25
        slow-call-rate-threshold: 25
        slow-call-duration-threshold: 250ms
        sliding-window-type: count_based
        permitted-number-of-calls-in-half-open-state: 20
        max-wait-duration-in-half-open-state: 5s
        sliding-window-size: 40
        minimum-number-of-calls: 10
        wait-duration-in-open-state: 2500ms
```

## Honorable mentions

- [Circuit Breaker Redis Cache by gee4vee](https://github.com/gee4vee/circuit-breaker-redis-cache)
- [Multilevel cache Spring Boot starter by pig777](https://github.com/pig-mesh/multilevel-cache-spring-boot-starter)

## Contributing

Pull requests are welcome. Before submitting, please run:

```bash
./gradlew spotlessApply check test
./gradlew jmh
```

The complete five-fork benchmark suite covers the operational cache API, invalidation, and
synchronized contention waves, and typically takes 25–30 minutes on a developer workstation.
