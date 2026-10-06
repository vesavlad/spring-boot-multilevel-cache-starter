# Changelog

## Unreleased

### Added

- Opt-in negative caching via `spring.cache.multilevel.cache-null-values` (default `false`).
  When enabled, `null` results are cached in Caffeine and Redis with the regular TTL.
- Per-cache overrides via `spring.cache.multilevel.caches.<name>.*` for `time-to-live`,
  `cache-null-values`, `local.max-size`, `local.time-to-live`, `local.expiry-jitter` and
  `local.expiration-mode`. Unset fields inherit the global values.
- `spring.cache.multilevel.circuit-breaker.enabled` (default `true`). When `false`, the breaker
  is created in the `DISABLED` state: Redis is attempted on every operation and failures still
  fall back to the local tier.

### Compatibility

- Default behavior is unchanged, except that Redis null markers written by instances with null
  caching enabled are treated as cache misses instead of failing deserialization.
- Enable `cache-null-values` only after every instance sharing the Redis keyspace runs a release
  that includes this change; instances on earlier releases may fail to deserialize cached nulls.
- The circuit-breaker open-state recommendation now uses the effective local TTL (global
  `local.time-to-live` when set) and is also checked for each overridden cache.

## 4.1.1.0

### Fixed

- Preserve L1-first reads while making connected cold-cache `putIfAbsent` races converge.
- Keep user loaders outside the Redis circuit breaker.
- Fall back only for Redis availability failures and propagate unexpected cache failures.
- Prevent lock eviction and stale Redis reads from repopulating invalidated L1 entries.
- Align Redis with the existing no-null cache contract.
- Decouple Pub/Sub invalidation from custom cache-value serializers.
- Prevent inbound invalidation messages from creating cache instances.
- Apply patterned clearing to both Redis and the local cache.
- Back off auto-configuration for user-managed caching and missing Redis infrastructure.

### Compatibility

- Existing public constructors, property names, bean names, Redis key layout, topic, and v0
  invalidation message remain unchanged.
- Existing custom cache-value serializer discovery remains unchanged.
- Rolling upgrades accept both stable JSON and legacy custom-serializer invalidation messages;
  publishers emit a best-effort legacy copy when the custom serializer supports the message type.
- `AFTER_CREATE`, `AFTER_UPDATE`, and `AFTER_READ` remain supported and unchanged.
- Asynchronous `Cache.retrieve(...)` methods are not yet multilevel-aware.
