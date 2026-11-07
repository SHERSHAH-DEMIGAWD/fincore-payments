# Milestone 10 — Authenticated projection promotion and rollback

Completed 2026-10-22 in **fincore-payments — Distributed Payment Processing Platform**. Final evidence combines the full-suite execution and a successful targeted rerun after a test-only assertion correction.

## 1. WHAT WAS IMPLEMENTED

- Authenticated HTTP route status, CSRF-token, promotion and rollback endpoints.
- Fresh fenced source/receipt/projection/gap checks before each new route switch.
- Atomic database read pointer, expected revision and immutable decision audit.
- Exact accepted-request replay bound to authenticated actor and original intent.
- One enrolled shadow plus baseline maintained in the same consumer transaction, including duplicate deliveries.
- Checked baseline rollback and re-promotion of the same warm shadow.
- Enrolled rebuild metadata frozen against concurrent/stale catch-up mutation.
- Single-statement routed projection reads; persistent teaching guide and operator runbook.

## 2. CONCEPTS / TECHNOLOGIES USED

- Spring Security / BCrypt / Basic / CSRF — scoped authenticated operator HTTP boundary.
- Advisory transaction locks / SHARE NOWAIT — cutover fencing and bounded contention.
- REQUIRES_NEW / REQUIRED / actual JDBC isolation — fresh independent switches and atomic consumer processing.
- Read Committed / Repeatable Read conflict — source freshness and stale-planner protection.
- Revision CAS / request idempotency — stale intent rejection and exact accepted replay.
- Atomic fanout / immutable audit / deferred constraints — maintained targets and consistent routing history.

## 3. IMPORTANT FILES

- `ProjectionRoutingController.java` — authenticated route status, CSRF, promote and rollback HTTP methods.
- `ProjectionOperationsSecurityConfiguration.java` — scoped credentials, role and CSRF enforcement.
- `StrictRoutingCounterDeserializer.java` — explicit integral HTTP revision/generation preconditions.
- `ProjectionRoutingService.java` / `ProjectionRoutingRepository.java` — fresh checks, fence, route revision and audit.
- `PaymentEventConsumer.java` / `PaymentEventConsumerRepository.java` — transactional warm fanout and routed reads.
- `ProjectionRebuildService.java` — shared gate and enrolled-run mutation checks.
- `V10__add_projection_routing.sql` — enrollment, route/audit tables and consistency/freeze guards.
- `ProjectionRoutingDatabaseTests.java` / `ProjectionRoutingControllerTest.java` — transaction/race and HTTP/security regression coverage.
- `src/test/resources/application.properties` — bounds cached test contexts' connection pools.
- `PROJECTION_ROUTING_RUNBOOK.md` / `MILESTONE_10_LEARNING_GUIDE.md` — usage, concepts, impact and alternatives; master guide/README/handoff updated.

## 4. REQUEST FLOW

```text
POST /api/v1/operations/projection-routing/promote
→ ProjectionRoutingController.promote (authenticated Principal)
→ ProjectionRoutingService.promote (REQUIRES_NEW / READ_COMMITTED)
→ ProjectionRoutingRepository.fence / equivalent / currentSourceMatches
→ enrollment + projection_routes + projection_route_audit COMMIT
→ PaymentEventConsumerRepository.findRoutedProjection on subsequent projection GET
```

## 5. IMPORTANT DESIGN DECISIONS

- Baseline keeps its durable processing/diagnostic identity; one lifetime shadow bounds fanout.
- Fresh comparisons and route/audit changes share the fenced transaction; prior validation is advisory.
- Switches own fresh transactions; consumers retain REQUIRED and verify actual JDBC Read Committed isolation.
- Both baseline and shadow must already be healthy/current/equivalent; damaged-baseline repair is separate work.
- Both targets stay warm after rollback; source writes still become projections asynchronously.
- Authenticated principal supplies actor; absent credentials deny operator access, and CSRF stays enabled.
- Enrollment changes the locked run row and freezes rebuild metadata to defeat stale planning snapshots.
- Single-statement reads select one committed route/data snapshot; history remains NOT_CERTIFIED.

## 6. TEST STATUS

- **534 distinct executed checks passed:** 307 unit/service/configuration/MVC/CLI; 215 real PostgreSQL invocations including 39 routing cases; 7 real Kafka/PostgreSQL; 5 packaged CLI. Final reports contain zero failures/errors.
- Latest full `verify` exercised the final production code and found one test expecting an unwrapped isolation-guard exception. Spring's repository wrapper was correct; a test-only assertion correction retained the message/no-write checks. The final 39-case routing rerun passed (`test-compile failsafe:integration-test failsafe:verify`, **BUILD SUCCESS**). No single final full `verify` rerun followed that assertion-only fix. An earlier full run passed 531 checks before the three additional isolation regressions.
- Initial rounds corrected PostgreSQL NOWAIT SQLSTATE `55P03` classification and test wrapper expectations, and bounded cached test pools after the database connection limit blocked CLI starts. Fresh V1–V10 migration/Hibernate validation and the legacy-upgrade regression passed; V1–V9 files were preserved.
- **224 Docker-dependent methods skipped.** Actual services: PostgreSQL 18.6 and Kafka 3.9.1; Spring Security 6.5.11. PostgreSQL 17.6 containers, multi-broker failover and production-volume/load behavior remain unverified. Flyway warns PostgreSQL 18 exceeds its tested support. The workspace test broker/cluster were stopped; ports 19092/55432 have no listeners, and unrelated PostgreSQL 5432 was untouched.
- Evidence: workspace `.tools/milestone10-release-verify.log` and `.tools/milestone10-routing-final.log`. Documentation QA passed **155 local links/anchors** and balanced code fences across nine current documents.

## 7. CURRENT PROJECT STATUS

**COMPLETED:** ✓ milestones 1–10 implementation: payment correctness through controlled read-model routing.

**CURRENTLY POSSIBLE:** ✓ promote one eligible shadow, maintain both targets and safely toggle reads with authenticated, audited decisions.

**NOT IMPLEMENTED YET:** ✗ enrolled-target replacement/retirement, automatic orchestration/retention, full merchant security, arbitrary correction or real provider/funds integration.

**NEXT LOGICAL MILESTONE:** controlled target replacement/retirement with catch-up ownership, coverage checks and measured operating limits.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Projection promotion, rollback and atomic reader routing
- Shared/exclusive advisory locks and SHARE NOWAIT
- Read Committed, Repeatable Read and serialization retry
- REQUIRED, REQUIRES_NEW and actual JDBC isolation
- Atomic fanout and warm rollback targets
- Revision CAS and idempotent authenticated commands
- Deferred constraints and immutable audit
- Spring Security filter chains, principals and roles
- BCrypt, HTTP Basic, TLS, CSRF tokens and sessions
