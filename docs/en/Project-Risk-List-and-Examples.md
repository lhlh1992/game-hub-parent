# Game Hub Project Risk List and Examples

> **Scope**: Covers risk identification across the entire `game-hub-parent` project, including Gateway, Game-service, System-service, Chat-service, frontend, infrastructure, and cross-service risks.

---

## I. Gateway Service Risks

### 1.1 JWT Blacklist Performance Bottleneck
- **Risk Level**: 🟡 **Medium** (triggered under high QPS)
- **Trigger Condition**: Starts to bottleneck at **2000+ QPS**
- **Risk**: Each request queries Redis blacklist; at high QPS this becomes a bottleneck.
- **Current Assessment**: Current config (16 Redis connections, local Redis) is **fully sufficient within 500 QPS**, pool usage ~1.25%, latency <1 ms. **Consider optimization when QPS > 2000**.
- **Example**: At 2000+ QPS, 4000+ Redis queries per second may cause pool queueing or latency.
- **Improvement** (only when QPS > 2000):
  - Use local cache (Caffeine) to cache blacklist with periodic refresh.
  - Or use Bloom Filter to quickly filter and reduce Redis queries.

### 1.2 Session Status Check Performance Issue
- **Risk Level**: 🟡 **Medium** (triggered under high QPS)
- **Trigger Condition**: Starts to bottleneck at **2000+ QPS**
- **Risk**: Session status check in JWT validation synchronously queries `SessionRegistry`, blocking requests.
- **Current Assessment**: Similar to blacklist; **within 500 QPS is sufficient**. **Consider optimization at 2000+ QPS**.
- **Example**: At 2000+ QPS, `checkSessionStatus()` in JWT decoder synchronously queries Redis, accumulating latency.
- **Improvement** (only when QPS > 2000):
  - Async session state check or reactive optimization.
  - Or encode session state into JWT claims to reduce queries.

### 1.3 WebSocket Auth Token Passing Security
- **Risk Level**: 🟠 **High** (security risk, anytime)
- **Trigger Condition**: **Anytime** (logging, packet capture)
- **Risk**: Token passed via URL param during WebSocket handshake may be logged or leaked.
- **Example**: `WebSocketTokenFilter` extracts `access_token` from URL; access logs may record full URL.
- **Improvement**:
  - Pass token via WebSocket subprotocol.
  - Or use one-time handshake token that expires immediately after handshake.

### 1.4 Missing Keycloak Failure Degradation
- **Risk Level**: 🔴 **Critical** (infra outage)
- **Trigger Condition**: **When Keycloak down or network broken**
- **Risk**: If Keycloak unavailable, Gateway cannot validate JWT; all requests rejected.
- **Example**: Keycloak down or network blocked, `issuer-uri` unreachable, JWT validation fails.
- **Improvement**:
  - Implement JWT public key cache; when Keycloak unavailable, validate with cached key.
  - Or implement degradation to allow configured “emergency whitelist”.

### 1.5 Hardcoded Route Config
- **Risk Level**: 🟢 **Low** (ops optimization)
- **Trigger Condition**: **Need dynamic routing**
- **Risk**: Routes hardcoded in `application.yml`; cannot change dynamically.
- **Example**: For canary or A/B, must restart service to change config.
- **Improvement**:
  - Use Spring Cloud Config or Nacos for dynamic routes.
  - Or provide route rule API to update dynamically.

---

## II. Game-service Risks

### 2.1 Multi-Node Consistency and Mutual Exclusion
- **Risk Level**: 🟠 **High** (multi-node + concurrency)
- **Trigger Condition**: **Multi-instance deployment + concurrent ops on same room**
- **Risk**: Critical actions (AI move, player move apply) executed concurrently on multiple nodes; may cause double-move or state races.
- **Example**: Both nodes think “AI’s turn”, each schedules AI and executes; both moves happen, only one persists, short-lived memory divergence.
- **Improvement**:
  - Add room-level distributed short lock before AI exec (SETNX `ai:lock:{roomId}` + TTL).
  - Or “single-room single-node routing”.
  - After CAS failure in place, immediately reload from Redis to refresh memory.

### 2.2 Countdown Restore Misfire
- **Risk Level**: 🟡 **Medium** (service restart)
- **Trigger Condition**: **On service restart** (already mitigated)
- **Risk**: If countdown keys not cleaned, restore may treat stopped tasks as active.
- **Example**: Countdown stopped but `countdown:{key}` remains; on restart `restoreAllActive` reschedules.
- **Current**: `stop()` already deletes `stateKey/holderKey`; risk reduced.

### 2.3 Using KEYS for Restore Scan
- **Risk Level**: 🟡 **Medium** (data volume)
- **Trigger Condition**: **Redis keyspace > 100k** causes blocking
- **Risk**: `redis.keys("countdown:*")` blocks in prod, not scalable.
- **Example**: Large keyspace, KEYS stalls Redis affecting global reads/writes.
- **Improvement**: Use SCAN cursor or maintain active set.

### 2.4 AI Branch CAS Condition Hardcoded
- **Risk Level**: 🟠 **High** (logic bug, anytime)
- **Trigger Condition**: **AI as black** triggers
- **Risk**: AI persistence uses fixed `expectedTurn=WHITE`; when mismatch, CAS fails, frontend lag.
- **Example**: AI turn is black but CAS expects white → updateAtomically fails; broadcast/storage inconsistent briefly.
- **Improvement**: Use `now.current()` for expectedTurn.

### 2.5 Turn Anchor Missing TTL/Cleanup
- **Risk Level**: 🟡 **Medium** (stale data)
- **Trigger Condition**: **After game end without cleanup**
- **Risk**: `turnKey` without TTL may leave stale anchors, confusing snapshot/restore.
- **Example**: After end, `gomoku:turn:{roomId}` not cleaned; new game/restore reads old deadline.
- **Improvement**: Set TTL or delete explicitly at end/restart/stop.

### 2.6 Memory vs Redis Temporary Divergence
- **Risk Level**: 🟠 **High** (multi-node + concurrency)
- **Trigger Condition**: **Multi-instance + concurrent move**
- **Risk**: Flow “memory Room mutate → Redis CAS”; another node mutates memory; CAS fails for one side but memory already dirty.
- **Example**: Two nodes place nearly simultaneously; loser of CAS must reload Redis or later logic uses dirty memory.
- **Improvement**: After CAS failure, force reload; or push critical mutation into Redis/Lua atomics.

### 2.7 User Auth Legacy Issue
- **Risk Level**: 🟢 **Low** (mostly done; naming)
- **Trigger Condition**: **seatKey leak + misuse** (risk reduced)
- **Current**: Seat binding uses `userId` (from Gateway JWT); main auth on userId.
- **Legacy**:
  - `SeatsBinding` fields named `seatXSessionId`/`seatOSessionId` (historical; actually store userId).
  - `seatKey` still exists, mainly for reconnect; leakage could be misused.
- **Improvement** (optional):
  - Rename fields to `seatXUserId`/`seatOUserId` for clarity.
  - Or remove `seatKey`, use userId-based recovery.

### 2.8 WebSocket Broadcast Order and Idempotency
- **Risk Level**: 🟡 **Medium** (concurrency)
- **Trigger Condition**: **Concurrent broadcasts**
- **Risk**: STATE/SNAPSHOT order may shuffle or duplicate.
- **Example**: Client receives old STATE then new SNAPSHOT; brief confusion.
- **Improvement**: Ensure server ordering; client idempotent apply by `gameId/step`.

### 2.9 Config and Threshold Tunability
- **Risk Level**: 🟢 **Low** (ops tuning)
- **Trigger Condition**: **Need env tuning**
- **Risk**: holder TTL, AI delay, countdown TTL hardcoded; hard to tune per env.
- **Improvement**: Externalize config; allow env/scene overrides.

---

## III. System-service Risks

### 3.1 Keycloak Event Handling Failure
- **Risk Level**: 🔴 **Critical** (event handling failure)
- **Trigger Condition**: **DB error or network during Keycloak callbacks**
- **Risk**: On failure, Keycloak user not synced to system DB.
- **Example**: `KeycloakEventController` receives register event; `KeycloakEventServiceImpl` fails (DB/network), user cannot log in.
- **Improvement**:
  - Implement event retry (Kafka or local queue).
  - Or periodic sync Keycloak users to system DB.

### 3.2 User Sync Consistency
- **Risk Level**: 🟠 **High** (data inconsistency)
- **Trigger Condition**: **Manual ops diverge between Keycloak and system DB**
- **Risk**: Inconsistent users cause login/permission errors.
- **Example**: User deleted in Keycloak but exists in system DB, or vice versa.
- **Improvement**:
  - Implement bidirectional sync.
  - Or use Keycloak as single source; system DB as cache.

### 3.3 Keycloak Admin API Call Failure
- **Risk Level**: 🟠 **High** (API failure)
- **Trigger Condition**: **Network/Keycloak unavailable during Admin API call**
- **Risk**: Create/update user fails after system DB updated → inconsistency.
- **Example**: `UserServiceImpl.createUser()` updates DB then Keycloak API fails; hard to rollback.
- **Improvement**:
  - Use Saga/compensation.
  - Or call Keycloak first, then update DB after success.

### 3.4 Unclear DB Transaction Boundaries
- **Risk Level**: 🟠 **High** (partial failure)
- **Trigger Condition**: **Complex ops partially fail**
- **Risk**: Complex ops (create user + assign role + init permissions) incomplete → inconsistent state.
- **Example**: User created, role assignment fails, user left half-done.
- **Improvement**:
  - Define boundaries, use `@Transactional`.
  - Or domain events to async non-critical steps.

### 3.5 Soft-Delete Residue
- **Risk Level**: 🟡 **Medium** (data volume)
- **Trigger Condition**: **Deleted data > 100k** impacts performance
- **Risk**: Soft-deleted rows linger, hurt queries/storage.
- **Example**: Many deleted users occupy space; queries must filter `deleted_at IS NULL`.
- **Improvement**:
  - Periodically purge/archive soft-deletes.
  - Or use partitions and move deleted to archive.

---

## IV. Chat-service Risks

### 4.1 Message Persistence Failure
- **Risk Level**: 🟠 **High** (data loss)
- **Trigger Condition**: **PostgreSQL/Redis write fails**
- **Risk**: Message sent but not persisted; history lost.
- **Example**:
  - Room message: `ChatHistoryService.appendRoomMessage()` Redis write fails → cannot recover.
  - Private message: `ChatSessionServiceImpl.savePrivateMessage()` Postgres write fails → lost.
- **Current**:
  - Room messages only Redis (no transaction); Redis fail loses message.
  - Private messages in Postgres (tx), but Redis cache may be inconsistent if fail.
- **Improvement**:
  - Add persistence retry.
  - Or transactional guarantee across Redis/Postgres; persist room messages to Postgres too.

### 4.2 System Notification Push Failure
- **Risk Level**: 🟢 **Low** (mitigated)
- **Trigger Condition**: **system-service → chat-service push fails**
- **Risk**: Notification stored but not pushed realtime.
- **Example**: `ChatNotifyClient.push()` fails (network/down); notification stored but not pushed.
- **Current**: ✅ Degrade via `ChatNotifyClientFallback`; push failure logged only; notification stored; offline view ok. Risk mitigated.
- **Improvement**:
  - ✅ Current is enough (fallback + storage).
  - Or add retry via Kafka queue.

### 4.3 User Info Cache Inconsistency
- **Risk Level**: 🟢 **Low** (resolved)
- **Trigger Condition**: **User info updated, cache not refreshed**
- **Risk**: chat-service Redis cache stale, shows old info.
- **Example**: User renames; system-service refreshes cache; chat-service not refreshed shows old nickname.
- **Current**: ✅ chat-service/system-service share same Redis (db 0) and key `user:profile:{keycloakUserId}`; once updated, both see it. Resolved.
- **Improvement**:
  - ✅ Current is enough (shared cache).
  - Or add cache invalidation event (Kafka).

### 4.4 WebSocket Connection Limit
- **Risk Level**: 🟡 **Medium** (conn count)
- **Trigger Condition**: **Single node WebSocket connections > 50,000**
- **Risk**: Single node limit; cannot support large concurrency.
- **Example**: Single host max ~65,535; above fails new connections.
- **Current**: chat-service and game-service both use WebSocket; limit shared.
- **Improvement**:
  - Load balance to spread connections.
  - Or WebSocket cluster with Redis shared sessions.

### 4.5 Private Message Idempotency
- **Risk Level**: 🟢 **Low** (resolved)
- **Trigger Condition**: **Client resend / network retransmit**
- **Risk**: Duplicate storage.
- **Current**: ✅ `clientOpId` idempotency check (`ChatSessionServiceImpl.savePrivateMessage()` lines 100-105); duplicates filtered, throws `IllegalStateException`.
- **Improvement**:
  - ✅ Current is enough.

### 4.6 Message History Query Performance
- **Risk Level**: 🟡 **Medium** (data volume)
- **Trigger Condition**: **History > 100k**
- **Risk**: Large history queries slow.
- **Example**: Private history query `limit=1000` scans large dataset.
- **Improvement**:
  - Use pagination, limit per query.
  - Or cache recent messages in Redis.

### 4.7 Room Event Subscription Failure
- **Risk Level**: 🟢 **Low** (feature degradation, Kafka issue)
- **Trigger Condition**: **Kafka unavailable or subscribe fails**
- **Risk**: chat-service fails to auto create/delete room chat sessions.
- **Example**: Kafka down, `RoomEventConsumer` not receiving events; room chat downgraded (manual create needed).
- **Improvement**:
  - Add retry for room event subscription.
  - Or provide manual API to create room session.

### 4.8 Private Chat Friend Validation Degrade Security
- **Risk Level**: 🟠 **High** (security; system-service unavailable)
- **Trigger Condition**: **system-service unavailable or friend check fails**
- **Risk**: Private message friend check fails; fallback allows send (`SystemUserClientFallback.isFriend()` returns `ApiResponse.success(true)`), enabling non-friend chat.
- **Example**: system-service down; `ChatMessagingServiceImpl.sendPrivateMessage()` calls `systemUserClient.isFriend()` fails; fallback allows; non-friends can chat.
- **Current**: For availability, fallback allows send (`SystemUserClientFallback.isFriend()` line 27 returns `ApiResponse.success(true)`; comment: prod should `return ApiResponse.success(false)` for safety).
- **Improvement**:
  - In production, change fallback to `return ApiResponse.success(false)` to ensure safety.
  - Or cache friend relations locally; when system-service unavailable, use cache.

### 4.9 WebSocket Long-Lived Token Expiry Risk
- Risk Level: 🟠 High (security and functional risk; inevitable on long-lived connections)
- Trigger Condition: **WebSocket connection duration > JWT validity period**, and during this period a downstream call (e.g., via Feign) requiring authorization is made.
- Risk: While handling messages on the long-lived connection, if the initial token has expired, Feign calls carrying the expired token are rejected by downstream services (401), leading to features (e.g., private chat) failing.
- Example: When sending a private message, `ChatMessagingServiceImpl` calls `systemUserClient.isFriend()` and it fails due to token expiry.
- Improvement:
  - Implement backend silent refresh: in a Feign interceptor, check the token expiry; if expired, use the Refresh Token to obtain a new access token, transparent to users and downstream services.

---

## V. Frontend (game-hub-web) Risks

> **Note**: Frontend risks numbered from 5.1, distinct from chat-service (section IV).

### 5.1 Token Refresh Failure Handling
- **Risk Level**: 🟡 **Medium** (network issues)
- **Trigger Condition**: **Token refresh fails when expired & network/Gateway unavailable**
- **Risk**: Auto-refresh fails; user forced logout; bad UX.
- **Example**: `authService.ensureAuthenticated()` refresh fails; user in-game gets logged out.
- **Improvement**:
  - Add retry; logout only after multiple failures.
  - Or use WebSocket heartbeat to refresh early.

### 5.2 WebSocket Reconnect State Loss
- **Risk Level**: 🟡 **Medium** (network interrupt)
- **Trigger Condition**: **WebSocket disconnect/reconnect**
- **Risk**: After reconnect, game state not synced; user sees wrong state.
- **Example**: Network drop then reconnect; `GomokuResumeController` `FullSync` incomplete.
- **Improvement**:
  - Force full state snapshot on reconnect.
  - Or keep local cache and diff on reconnect.

### 5.3 Concurrent Request Race Conditions
- **Risk Level**: 🟡 **Medium** (fast user actions)
- **Trigger Condition**: **User rapid actions**
- **Risk**: Concurrent API responses unordered, causing state confusion.
- **Example**: Create room and join room fired together; create fails but join succeeds, or vice versa.
- **Improvement**:
  - Request queue to serialize critical ops.
  - Or optimistic locking; client conflict handling.

### 5.4 Memory Leak
- **Risk Level**: 🟡 **Medium** (long run)
- **Trigger Condition**: **Long runtime or frequent page switches**
- **Risk**: WS subscriptions, timers, listeners not cleaned, causing leaks.
- **Example**: `useGomokuGame` subscribes WS; on unmount not unsubscribed.
- **Improvement**:
  - Use `useEffect` cleanup to release resources.
  - Or use React 18+ `useSyncExternalStore` for external state.

---

## VI. Infrastructure Risks

> **Note**: Infra risks numbered from 6.1, distinct from frontend (section V).

### 6.1 Redis Single-Point Failure
- **Risk Level**: 🔴 **Critical** (infra outage)
- **Trigger Condition**: **Redis down**
- **Risk**: Redis SPOF makes all services unavailable (sessions, game state, countdown).
- **Example**: Redis down; Gateway cannot validate session; Game-service cannot read state.
- **Improvement**:
  - Use Redis Sentinel or Cluster for HA.
  - Or degradation: key data also in DB.

### 6.2 PostgreSQL Data Consistency
- **Risk Level**: 🟠 **High** (replication lag)
- **Trigger Condition**: **Primary-secondary lag > 1s**
- **Risk**: Lag or wrong isolation → inconsistency.
- **Example**: Write on primary, read from secondary before sync; user missing.
- **Improvement**:
  - Force read primary for critical ops.
  - Or use distributed TX (Seata) for consistency.

### 6.3 Kafka Message Loss
- **Risk Level**: 🟠 **High** (loss)
- **Trigger Condition**: **Kafka not persisted or without replicas**
- **Risk**: Lost Kafka messages → session events not delivered.
- **Example**: Logout event lost; others think user still online.
- **Improvement**:
  - Configure Kafka persistence and replicas.
  - Or implement ack/confirm to ensure delivery.

### 6.4 Keycloak Availability
- **Risk Level**: 🔴 **Critical** (infra outage)
- **Trigger Condition**: **Keycloak down or network blocked**
- **Risk**: Users cannot log in.
- **Example**: Keycloak down, Gateway cannot validate JWT.
- **Improvement**:
  - Keycloak cluster.
  - Or JWT public key cache; use cached key during short outage.

### 6.5 Docker Compose Single-Host Limit
- **Risk Level**: 🟡 **Medium** (resource exhaustion)
- **Trigger Condition**: **Single host resource exhausted / need scale-out**
- **Risk**: Single-host Compose cannot scale horizontally.
- **Example**: Host resources exhausted; cannot add nodes.
- **Improvement**:
  - Move to Kubernetes for horizontal scaling.
  - Or Docker Swarm multi-node.

---

## VII. Cross-Service Risks

> **Note**: Cross-service risks numbered from 7.1, distinct from infra (section VI).

### 7.1 Inter-Service Call Failures
- **Risk Level**: 🟢 **Low** (mitigated)
- **Trigger Condition**: **Downstream unavailable or network issues**
- **Risk**: Call failures → degraded/failed features.
- **Example**:
  - Game-service → System-service fails to get user info.
  - System-service → Chat-service push fails; notification stored but not pushed.
  - Chat-service → System-service fails; uses cache.
- **Current**:
  - ✅ Game-service → System-service: Resilience4j circuit breaker (`SystemUserClient`), cache fallback.
  - ✅ System-service → Chat-service: Fallback (`ChatNotifyClientFallback`); failure doesn’t affect main TX; notification stored.
  - ✅ Chat-service → System-service: Resilience4j (`SystemUserClient`), cache fallback.
  - **Risk Mitigated**: All inter-service calls have circuit breaker/fallback.
- **Improvement**:
  - ✅ Current sufficient (breaker + fallback); no extra needed.
  - Or finer retry policy.

### 7.2 Event Loss or Duplication
- **Risk Level**: 🟠 **High** (Kafka issue)
- **Trigger Condition**: **Kafka message lost or duplicated consumption**
- **Risk**: Lost/duplicate events → inconsistent data.
- **Example**: Session invalidation lost; Gateway not cleaned; user still access.
- **Improvement**:
  - Enable idempotent production (`enable.idempotence=true`) + `acks=all` on producers so retries don’t create duplicate messages and messages are durably replicated;
  - Design a unique `eventId` per event (e.g. `userId + loginSessionId + timestamp`) and record processed IDs in Redis/DB so consumers can **idempotently** skip duplicates;
  - For purely idempotent side effects (e.g. disconnecting a WebSocket) it’s acceptable to rely on operation-level idempotency, but any future non-idempotent logic (stats, rewards) must use `eventId`-based de-duplication;
  - Or use event sourcing to rebuild state.

### 7.3 Idempotency Design Fragmentation (HTTP / WebSocket / Kafka)
- **Risk Level**: 🟡 **Medium** (network jitter / retries)
- **Trigger Condition**: **HTTP/WS timeouts & retries, browser refresh/multi-tab/multi-device, Kafka retries/rebalances**
- **Risk**:
  - Different layers use different ad-hoc idempotency patterns (CAS, `clientOpId`, Kafka idempotent producer) without a unified design, which under high concurrency or multi-instance deployments can lead to:
    - HTTP APIs creating duplicate resources (duplicate rooms, duplicate friend requests);
    - WebSocket commands (ready/start/kick, etc.) being re-applied after reconnect, causing state flapping;
    - Kafka events being re-consumed and non-idempotent side effects (e.g. future stats/rewards) executing multiple times.
- **Current Assessment**:
  - **State idempotency (Game / Redis)**: moves rely on Redis CAS (`expectedStep + expectedTurn`) so the same move cannot be applied twice;
  - **Request idempotency (HTTP / WS)**: private messages use `clientOpId` for idempotency, but other create/mutate APIs mostly rely on state checks and frontend throttling, with no unified `idempotency-key` scheme;
  - **Message idempotency (Kafka)**: producers use idempotent production with `acks=all`, consumers mostly rely on “disconnect WS is idempotent” and don’t de-duplicate events explicitly.
- **Improvement**:
  - In **Gateway Filters / Spring MVC interceptors**, standardize reading an `X-Idempotency-Key` / `requestId` header for create/one-off mutation APIs (create room, friend request, etc.), and use Redis `SETNX` (or DB) to record processed requests so duplicates either return the first result or are rejected;
  - For critical WS commands (ready/start/restart/kick/resign, etc.), extend payloads with `clientOpId` and de-duplicate on the server side to avoid state flapping on reconnect/retry;
  - For Kafka events, introduce a unified `eventId` field and track processed IDs in Redis/DB so consumers achieve true **message-level idempotency**, complementing the producer-side idempotence;
  - Document the three layers of idempotency (state / request / message) and their respective mechanisms so new APIs/events follow the same pattern.

### 7.4 Data Consistency
- **Risk Level**: 🟠 **High** (inconsistency)
- **Trigger Condition**: **Manual cross-service data divergence**
- **Risk**: Cross-service data inconsistent (Keycloak vs system DB).
- **Example**: User deleted in Keycloak but exists in system DB.
- **Improvement**:
  - Saga or distributed TX.
  - Or periodic sync for eventual consistency.

### 7.5 Service Version Compatibility
- **Risk Level**: 🟠 **High** (version upgrade)
- **Trigger Condition**: **API incompatibility during upgrades**
- **Risk**: Upgrades break calls.
- **Example**: Game-service changes API; Gateway still calls old interface.
- **Improvement**:
  - API versioning (`/v1/`, `/v2/`).
  - Contract testing (Pact) for compatibility.

---

## VIII. Observability and Ops Risks

> **Note**: Observability/Ops risks numbered from 8.1, distinct from cross-service (section VII).

### 8.1 Insufficient Observability
- **Risk Level**: 🟡 **Medium** (troubleshooting)
- **Trigger Condition**: **Need to diagnose prod issues**
- **Risk**: Lacking core metrics/traces makes issues hard to locate.
- **Example**: Hard to pinpoint node/time for “double AI” or “countdown misjudge”.
- **Improvement**: Add metrics (online/room/WS/failure/latency), log standard, TraceId, alerts.

### 8.2 Missing Log Standard
- **Risk Level**: 🟡 **Medium** (traceability)
- **Trigger Condition**: **Need request tracing**
- **Risk**: Logs not unified; no TraceId; hard to trace.
- **Example**: Mixed service logs cannot correlate same request.
- **Improvement**:
  - Unified JSON logs with TraceId/SpanId.
  - Or use OpenTelemetry for distributed tracing.

### 8.3 Missing Alerts
- **Risk Level**: 🟡 **Medium** (late detection)
- **Trigger Condition**: **Service issues without alerts**
- **Risk**: No alerts → slow detection.
- **Example**: Redis pool exhausted; no alert; discovered after failures.
- **Improvement**:
  - Integrate Prometheus + AlertManager.
  - Or cloud monitoring (Aliyun, AWS CloudWatch).

### 8.4 Configuration Management Chaos
- **Risk Level**: 🟢 **Low** (ops optimization)
- **Trigger Condition**: **Config errors/env drift**
- **Risk**: Config scattered; env differences cause errors.
- **Example**: Dev vs prod config mismatch causes prod issues.
- **Improvement**:
  - Use Spring Cloud Config or Nacos.
  - Or env vars via CI/CD injection.

---

## IX. Security Risks

> **Note**: Security risks numbered from 9.1, distinct from observability (section VIII).

### 9.1 JWT Token Leakage
- **Risk Level**: 🔴 **Critical** (security, anytime)
- **Trigger Condition**: **Token logged or captured**
- **Risk**: Leaked JWT lets attacker impersonate.
- **Example**: Token logged to files.
- **Improvement**:
  - Avoid logging full token.
  - Or short-lived token + refresh to reduce impact.

### 9.2 SQL Injection
- **Risk Level**: 🔴 **Critical** (security, anytime)
- **Trigger Condition**: **String-concat SQL with unfiltered input**
- **Risk**: SQL injection.
- **Example**: `@Query` with string concat, not parameterized.
- **Improvement**:
  - Use JPA method queries or parameterized queries.
  - Or MyBatis without string concat.

### 9.3 XSS
- **Risk Level**: 🟠 **High** (user input)
- **Trigger Condition**: **User injects script; frontend not escaped**
- **Risk**: Frontend not escaping user input → XSS.
- **Example**: Chat message with malicious script executed when viewed.
- **Improvement**:
  - Use React auto-escape or DOMPurify sanitize HTML.
  - Or Content Security Policy (CSP).

### 9.4 CSRF
- **Risk Level**: 🟠 **High** (malicious site)
- **Trigger Condition**: **Malicious site forges requests**
- **Risk**: No CSRF protection; attacker can forge actions.
- **Example**: Logged-in user; malicious site issues requests.
- **Improvement**:
  - CSRF token or SameSite cookie.
  - Or OAuth2 standard flow to reduce CSRF.

---

## X. Performance Risks

> **Note**: Performance risks numbered from 10.1, distinct from security (section IX).

### 10.1 DB Connection Pool Exhaustion
- **Risk Level**: 🟠 **High** (high concurrency)
- **Trigger Condition**: **Concurrent requests > pool size × 10**
- **Risk**: Pool exhaustion → failures.
- **Example**: 1000 concurrent, pool 20, many waiting.
- **Improvement**:
  - Tune pool size, monitor pool.
  - Or read/write split to reduce primary load.

### 10.2 Redis Memory Overflow
- **Risk Level**: 🟡 **Medium** (data volume)
- **Trigger Condition**: **Redis memory > 80%** triggers eviction
- **Risk**: High memory triggers eviction; impacts performance. chat-service and game-service share Redis; pressure accumulates.
- **Example**: Many game states, sessions, chat history; Redis low memory.
- **Improvement**:
  - Set proper TTLs; clean expired data.
  - Or Redis Cluster sharding.
  - Or separate Redis databases per service.

### 10.3 WebSocket Connection Limit
- **Risk Level**: 🟡 **Medium** (conn count)
- **Trigger Condition**: **Single node WebSocket connections > 50,000**
- **Risk**: Single node limit; both chat-service and game-service share WS; above limit fails.
- **Example**: Single node max ~65,535; beyond fails new connections.
- **Improvement**:
  - Load balance to spread connections.
  - Or WebSocket cluster with Redis session share.

---

## XI. Risk Level Statistics

### Risk Level Distribution

| Level | Count | Items |
|------|------|--------|
| 🔴 **Critical** | 6 | 1.4 Keycloak failure degradation<br>3.1 Keycloak event handling failure<br>6.1 Redis SPOF<br>6.4 Keycloak availability<br>9.1 JWT Token leakage<br>9.2 SQL injection |
| 🟠 **High** | 17 | 1.3 WebSocket token passing security<br>2.1 Multi-node consistency/mutex<br>2.4 AI CAS hardcoded<br>2.6 Memory vs Redis divergence<br>3.2 User sync consistency<br>3.3 Keycloak Admin API failure<br>3.4 DB transaction boundaries<br>4.1 Message persistence failure<br>4.8 Private chat friend fallback risk<br>4.9 WebSocket Long-Lived Token Expiry Risk<br>6.2 PostgreSQL consistency (lag)<br>6.3 Kafka message loss<br>7.2 Event loss/dup<br>7.4 Data consistency<br>7.5 Service version compatibility<br>9.3 XSS<br>9.4 CSRF<br>10.1 DB pool exhaustion |
| 🟡 **Medium** | 17 | 1.1 JWT blacklist perf (2000+ QPS)<br>1.2 Session check perf (2000+ QPS)<br>2.2 Countdown restore<br>2.3 KEYS scan (keyspace >100k)<br>2.5 Turn TTL/cleanup<br>2.8 WS broadcast order/idempotency<br>3.5 Soft-delete residue (>100k)<br>4.4 WS connection limit (>50k)<br>4.6 Message history perf (>100k)<br>5.1 Token refresh failure<br>5.2 WS reconnect state loss<br>5.3 Concurrent request races<br>5.4 Memory leak<br>6.5 Docker Compose single host<br>7.3 Idempotency design fragmentation (HTTP / WebSocket / Kafka)<br>8.1 Observability lacking<br>8.2 Log standard lacking<br>8.3 Alerts missing<br>10.2 Redis memory overflow (>80%)<br>10.3 WS connection limit (>50k, shared) |
| 🟢 **Low** | 9 | 1.5 Hardcoded routes<br>2.7 Auth legacy naming<br>2.9 Config tunability<br>4.2 Notification push failure (mitigated)<br>4.3 User cache inconsistency (resolved)<br>4.5 Private msg idempotency (resolved)<br>4.7 Room event subscribe failure<br>7.1 Inter-service call failure (mitigated)<br>8.4 Config management chaos |

### Trigger Condition Stats

| Trigger | Count | Notes |
|---------|------|------|
| **High concurrency** | 4 | 1.1, 1.2, 10.1, 10.3 |
| **Multi-node** | 2 | 2.1, 2.6 |
| **Data volume** | 4 | 2.3, 3.5, 4.6, 10.2 |
| **Infra failure** | 3 | 1.4, 6.1, 6.4 |
| **Security** | 6 | 1.3, 4.8, 9.1, 9.2, 9.3, 9.4 |
| **Service unavailable** | 3 | 4.2, 4.8, 7.1 |
| **Anytime** | 15+ | Other logic errors, inconsistencies, etc. |

---

## XII. Suggested Priorities

### P0 (Critical)
1. **🔴 Critical**: **System-service**: Keycloak event retry (3.1).
2. **🔴 Critical**: **Gateway**: Keycloak failure degradation (1.4).
3. **🔴 Critical**: **Security**: SQL injection defense (9.2), JWT leakage defense (9.1).
4. **🟠 High**: **Game-service**: Room-level lock for AI; AI CAS use `now.current()` (2.1, 2.4).

### P1 (High)
1. **🔴 Critical**: **Infra**: Redis HA (Sentinel/Cluster) (6.1).
2. **🔴 Critical**: **Infra**: Keycloak cluster or key cache (6.4).
3. **🟠 High**: **Game-service**: After place CAS fail, force reload; or consider Lua atomic (2.6).
4. **🟠 High**: **System-service**: Keycloak Admin API compensation (3.3).
5. **🟠 High**: **Chat-service**: Private chat friend fallback → reject in prod (4.8).
6. **🟡 Medium**: **Game-service**: Countdown SCAN; turnKey TTL/cleanup (2.3, 2.5).

### P2 (Planned)
1. **🟠 High**: **Cross-service**: Event loss/dup handling (7.2).
2. **🟠 High**: **Cross-service**: Data consistency (7.3).
3. **🟠 High**: **Chat-service**: Message persistence failure (4.1) - Persist room messages to Postgres and ensure transactional guarantee.
4. **🟡 Medium**: **Gateway**: JWT blacklist performance optimization (only if QPS > 2000; within 500 QPS no need) (1.1).
5. **🟡 Medium**: **Observability**: Metrics/logs/TraceId/alerts; unify serialization as JSON (8.1, 8.2, 8.3).
6. **🟢 Low**: **Cross-service**: Circuit breaker/retry/fallback (Resilience4j) (7.1) - ✅ already mitigated.
7. **🟢 Low**: **Game-service**: Rename `SeatsBinding` fields (`seatXSessionId` → `seatXUserId`) for readability (2.7).

### P3 (Long-term)
1. **🟡 Medium**: **Infra**: Move to Kubernetes for scale (6.5).
2. **🟡 Medium**: **Performance**: DB read/write split, Redis Cluster (10.1, 10.2).
3. **🟢 Low**: **Config**: Unified config management (Spring Cloud Config/Nacos) (1.5, 2.9, 8.4).

---

## XIII. Risk Tracking

Plan to track risks via:
- **Risk register**: record ID, description, impact, priority, owner, status.
- **Regular review**: monthly review, update status/priority.
- **Issue tracking**: Jira/GitHub Issues to track improvements.

---

## XIV. Multi-Instance Deployment Risk Overview (Summary)

> **For details, see**: `docs/en/1.0-Multi-Instance-Deployment-Impact-Analysis.md`. This section is a high-level summary of the core problem types. For per-feature impact and concrete upgrade plans, refer to the dedicated multi-instance analysis document.

For version 1.0, the core risks under multi-instance deployment can be summarized into four categories:

1. **Missing Room Affinity**  
   - HTTP / WebSocket requests for the **same room** may be routed to **different instances** under load balancing, while business code implicitly assumes “the same room stays on one instance”.  
   - Impact: room-level logic such as AI delayed moves and countdown restore may be executed once per instance, leading to duplicate execution or state flapping.  
   - Typical mitigation: implement room-affinity routing in Gateway based on `roomId` (e.g., consistent hashing).

2. **In-Memory State Not Shared Across Instances**  
   - Key state (such as `rooms`, `pendingAi`, local caches, state held by schedulers) is stored in each instance’s JVM memory with no unified persistence in Redis or event-based synchronization.  
   - Impact: for the same room, the Room / countdown / AI state in different instances may diverge, causing dirty memory, duplicate or missing tasks.  
   - Typical mitigation: move “source of truth” to Redis with CAS / distributed locks; treat in-memory state as cache only, optionally with versioning or forced reload.

3. **Non-Uniform Idempotency & Retry Strategy (HTTP / WS / Kafka)**  
   - Only a few spots implement idempotency (e.g., move CAS, private chat `clientOpId`); overall, HTTP / WebSocket / Kafka lack a unified idempotency design.  
   - Impact: under network jitter, timeouts and retries, multi-tab / multi-device usage, and Kafka retries/rebalances, the system may create duplicate rooms/friend requests, re-apply WS commands, and re-consume events that later carry non-idempotent logic (stats, rewards).  
   - Typical mitigation: unify three layers of idempotency—**state idempotency (Redis CAS)**, **request idempotency (`X-Idempotency-Key` + Redis SETNX)**, and **message idempotency (`eventId` + Redis de-dup)**.

4. **WebSocket Broadcast Limited to Single Instance (SimpleBroker Limitations)**  
   - game-service and chat-service both use `enableSimpleBroker("/topic", "/queue")`; SimpleBroker manages subscriptions and broadcasts **only inside one instance**.  
   - Impact: the same `/topic/...` destination effectively becomes “one small island per instance” under multi-instance:  
     - Lobby `/topic/chat.lobby` becomes “one lobby per instance”;  
     - Game `/topic/room.{roomId}` / room operations / countdown TICK/TIMEOUT: if room members connect to different instances, they cannot see each other’s messages or state updates.  
   - Typical mitigation: upgrade STOMP broadcasting from in-memory SimpleBroker to an external broker (RabbitMQ StompRelay / Redis PubSub, etc.), or temporarily combine external broker with room affinity to reduce the impact before full migration.

> **Usage tip**: In architecture reviews or interviews, you can first use this section to explain the four major risk types, then refer to `1.0-Multi-Instance-Deployment-Impact-Analysis.md` when you need to dive into “specific feature + code location + upgrade plan”. The two documents are meant to be used together.

---

> **Document Maintenance**: Keep this doc updated as project evolves; add new risks promptly; mark resolved risks with solutions.
