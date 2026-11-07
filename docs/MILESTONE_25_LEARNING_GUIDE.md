# Milestone25 — reproducible deployment and final validation

Implemented 28 October 2026. Continue from [M24 logical recovery](MILESTONE_24_LEARNING_GUIDE.md). Use the [deployment runbook](DEPLOYMENT_RUNBOOK.md), [complete project map](FINAL_PROJECT_GUIDE.md), [final validation report](FINAL_VALIDATION_REPORT.md), [concise summary](MILESTONE_25_SUMMARY.md) and [roadmap](ROADMAP.md). This closes the agreed functionality roadmap through25; outstanding environment validation is recorded separately.

## 1. Why this final block exists

A project can have individually correct services and still fail when launched with real credentials, network destinations, workers and packaging. M25 asks whether the assembled executable can demonstrate the original payment objective: authenticate a merchant, accept one intent, survive an ambiguous provider reply, apply confirmed accounting once, deliver/recover notifications and compare independent provider evidence.

It also asks a release question: are the bytes deployed the same bytes tested? A developer's unrecorded source checkout and mutable runtime tags cannot answer that. The new release script tests a clean build, records actual JUnit evidence, binds a deployable JAR by SHA-256 and creates an allowlisted secret-free ZIP. A standalone deployment recipe supplies dedicated demo identities and stable external keys. Nothing in this milestone executes real payments or adds financial schema: V22 stays current.

## 2. Artifact identity, reproducibility and trust

`Build-LocalRelease.ps1` requires Java21, starts `clean verify`, rejects stale/failed/empty test evidence, copies a fixed file set and creates `release-manifest.json` plus `SHA256SUMS`. `Verify-LocalRelease.ps1` rejects missing/extra checksum entries, duplicate paths, unexpected relative paths, changed bytes and a manifest that disagrees with the JAR. The Dockerfile verifies the required JAR hash again before creating the non-root runtime.

These are distinct properties:

| Property | Evidence provided | Limit |
| --- | --- | --- |
| Files agree with an expected reference | SHA-256 checks and exact release allowlist | Replacing files and their sole reference can still agree |
| Artifact was tested | Manifest points to the clean verify run and exact JAR | Skipped suites remain skipped; manifest custody is still required |
| Same inputs can reproduce JAR bytes | Fixed Maven output timestamp; two clean same-toolchain builds compared in final evidence | Not a claim about arbitrary vendors, hosts, dependencies or generated runtime reports |
| Runtime inputs stay fixed | Java/Postgres/Redis/Kafka image digests | Digests do not automatically patch vulnerabilities or certify images |

The manifest's `productionReadinessCertified=false` and verifier's `authenticityAttested=false` are deliberate. Hashing is not signing. Manifest creation time, generated demo passwords/UUIDs and runtime timings vary; the ZIP is a delivery artifact, not promised byte-identical across runs. Pinned inputs need explicit maintenance and retesting. A separate in-container Maven build was not selected: it could rebuild different bytes after host verification and requires a larger toolchain in the build path. A verified prebuilt JAR keeps that binding inspectable. [Dockerfile reference](https://docs.docker.com/reference/dockerfile/).

## 3. Headless command routing and safe bootstrap

`FincorePaymentsApplication.main` dispatches `init-local-sandbox`, `bootstrap-local-sandbox`, `probe-readiness` and `sandbox-acceptance` before Spring starts. Malformed commands exit2 with fixed refusal text; they do not accidentally start API workers against inherited database configuration. Existing provider/preservation/recovery commands retain their dispatch paths.

`LocalSandboxCli` creates a new credential directory once. Cryptographically random originals, BCrypt verifiers, a32-byte AES key and stable UUIDs serve different purposes; a BCrypt verifier cannot replace a provider signing key or encryption master key. Secrets are passed through environment/file inputs, never shell command arguments or console JSON. File inputs are bounded, strict UTF-8 and refuse ambiguous plain-plus-file values.

Bootstrap only accepts the dedicated `fincore_demo` database namespace on loopback or the fixed Compose PostgreSQL host. Flyway migrates it before one JDBC transaction inserts both named merchants. A cooperative PostgreSQL transaction advisory lock excludes concurrent bootstrap owners. An existing enabled account must match the original password; bootstrap does not rotate credentials or adopt a disabled/unrelated account. Both-account atomicity prevents a partially provisioned pair from being reported complete. Stable secrets are therefore part of the retained namespace, not disposable setup output.

The advisory lock coordinates this tool's bootstrap attempts; it is not an authorization mechanism against a database administrator. Creating fresh database roles and controlling privileges remain operator responsibilities. The native test role is deliberately a local test role.

## 4. Container topology without relaxing adapter contracts

The deployment uses a separate `deployment/compose.yml`, preserving existing development Compose recipes. PostgreSQL and Kafka communicate on a private network. API publishes one loopback host port. Provider, Redis and one-shot acceptance share `network_mode: service:api`: their literal `127.0.0.1` destinations refer to the API namespace.

This matters because the existing local adapters intentionally reject arbitrary remote hosts. Replacing those checks with broad container-DNS exceptions would enlarge allowed destinations. Sharing the namespace preserves that contract at the cost of lifecycle coupling: those services depend on the API container namespace, cannot independently publish ports there, and are a local demonstration topology rather than a high-availability design. [Compose network modes](https://docs.docker.com/reference/compose-file/services/#network_mode).

The Java runtime runs as uid10001 with a read-only root, bounded temporary space, dropped capabilities and explicit writable data/evidence volumes. Kafka uses its image-owned `/var/lib/kafka/data` volume path so the non-root broker need not create a root-owned arbitrary data directory. Docker logs rotate, but durable business evidence is retained. PostgreSQL, provider, Kafka and receiver storage have different recovery responsibilities. A single `down` retains volumes; deleting all volumes to obtain a clean demo would destroy the very evidence earlier milestones protect. [Apache Kafka image source](https://github.com/apache/kafka/blob/3.9.1/docker/jvm/Dockerfile).

Compose file-backed secrets do not provide general uid/gid remapping or a managed secret vault. The initializer makes a private POSIX parent and readable individual files for non-root mounts; Windows relies on its parent ACL. Only acceptance receives original merchant/operator passwords. API health receives the observer original; the API receives merchant-independent verifier/signing/encryption configuration. This narrows credential distribution without claiming total process isolation inside the shared network namespace. [Compose secrets](https://docs.docker.com/reference/compose-file/services/#secrets).

## 5. The packaged end-to-end acceptance flow

```text
java -jar ... sandbox-acceptance [six explicit local options]
→ SandboxAcceptanceCli.run → Exercise.execute
→ observer readiness + merchant/role/CSRF checks
→ scoped notification subscription + MerchantWebhookSink
→ real POST /api/v1/payments and exact/conflicting retries
→ provider AUTHORIZE → CAPTURE TIMEOUT_AFTER_ACCEPT → UNKNOWN
→ signed explicit callback redelivery twice → APPLIED
→ provider REFUND + exact retry → two full-payment journals
→ signed independent statement import/report → matching evidence
→ separate local partial captures/refunds → retained fees
→ receiver outage → DEAD delivery → restart + authorized retry
→ same-key and distinct concurrent creation → outbox/projection checks
→ pause subscription → new bounded JSON evidence
```

The provider's new `FINCORE_SANDBOX_WEBHOOK_AUTO_SEND=false` option makes the uncertainty drill deterministic. Its ordinary default remains true. Explicit redelivery still sends a genuine signed callback to the normal API endpoint; security and duplicate handling are exercised rather than bypassed. An HTTP200 provider-operation response alone is insufficient: acceptance requires the durable state UNKNOWN first and APPLIED after confirmation.

The partial-payment example captures40 with fee2, then60 with fee3; it refunds each principal against its own capture. Final principal is fully refunded while fees5 remain and merchant payable is-5 under the established accounting rules. That protocol remains separate from full-amount provider commands; the guide does not imply provider partial support.

## 6. Browser state was a real integration failure

Early acceptance attempts failed before meaningful payment work or during concurrent load. Shared cookie state let different credentials/workers observe incompatible CSRF sessions. Reusing a masked CSRF token from one session is not sufficient when another request changes the active session cookie.

The final client has a separate merchant-B browser, a merchant-A browser per worker thread and a cookie-free machine client. Before each authenticated merchant write it obtains a fresh token using the same browser session that sends the write. The deliberate no-CSRF test still expects403. Disabling CSRF or changing API authentication would have concealed a real client bug. This is why testing the assembled HTTP protocol matters even after controller/service tests pass.

CSRF addresses browser credential/session behavior; it is separate from tenant ownership and callback HMAC verification. Acceptance checks those separately: foreign payment/statement reads404, observer/operator credentials do not become merchant credentials, merchant credentials do not access private metrics, and forged callbacks401.

## 7. Bounded work, whole-response deadlines and evidence

Acceptance reserves a new report path before writes, caps requests at100 and concurrency at8, limits response bodies to2MiB and uses complete-response deadlines. `SandboxHttp` collects through a bounded Flow subscriber and cancels on oversized bodies or deadline expiry. Merely setting a header/request timeout while reading an unrestricted InputStream could hang after headers; a stalled-body unit test protects this boundary.

The command has a nominal180-second exercise deadline with per-request caps. Cleanup, OS scheduling and child-process behavior are not a hard realtime guarantee. Failed reports use fixed stages and, for HTTP mismatches, expected/actual statuses. They avoid raw bodies, credentials, private paths and underlying stack diagnostics.

The smoke workload races one idempotency key, then creates24 distinct payments with4 workers by default. It requires one logical same-key creation and unique distinct-payment IDs. Recorded p50/p95/max include browser-token work. They establish successful bounded workflows on this host; no representative traffic mix, long soak, saturation curve, failure budget or production capacity was measured. Reports explicitly state `productionCapacityValidated=false`.

## 8. Recoverable outgoing notifications

The same stable subscription request UUID finds the previously created owned subscription on reruns. The receiver journal is keyed by its subscription UUID and verifies signatures/delivery identity. Acceptance waits for event enrollment and DELIVERED state, stops the receiver, waits for a new notification to become DEAD under the short demo policy, restarts the same receiver and requests an authorized retry generation.

Successful completion pauses this subscription. On failure cleanup is best effort, and active/dead work may remain for diagnosis. Run one acceptance owner per namespace; a receiver port/journal lock is not a general cross-process orchestration service. A rerun uses a new report file while retaining keys, subscription and journals. Deleting evidence would make the test easier to repeat but destroy recovery attribution.

## 9. Risk, Kafka and what actually ran

The Compose acceptance requires a real ALLOW risk decision and a final owned Kafka projection. The native drill uses real PostgreSQL18.6, a real Kafka3.9.1 process, a packaged API process, a separate file-journal provider process and a real signed receiver. It deliberately turns risk off because this host has no real Redis runtime, and records that fact in evidence.

Docker-dependent tests remain skipped here. Compose parsing with the official CLI validates configuration structure, interpolation and references; it cannot prove Linux permissions, image build behavior, Redis Lua behavior or runtime health ordering. The supplied CI recipe is the next environment-validation step, not an executed success. Final exact test counts, JAR hashes, smoke timings, migration checks and cleanup evidence belong in the [dated validation report](FINAL_VALIDATION_REPORT.md).

## 10. CI and release custody

The repository-root workflow selects Java21, runs release verification on an Ubuntu Docker runner, builds from the exact verified JAR and executes combined Redis/Kafka acceptance. Official actions are pinned to commit IDs; permissions are read-only for repository contents. Uploads allow only the ZIP created before secret initialization and the bounded acceptance JSON. Diagnostic logs, credentials and an initialized bundle directory are excluded. The workflow file is local implementation; it has not run or been published in this session. [GitHub workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax).

This is repeatable local delivery, not registry publication, supply-chain attestation or deployment to an external environment. A future operator must establish trusted artifact custody, dependency/image refresh policy, secret rotation and access control according to that environment.

## 11. Holistic completion and next work

M1–21 established payment/evidence correctness under specific failure boundaries. M22 limits new admission, M23 explains stalled work, M24 demonstrates supported logical reconstruction, and M25 joins those paths into a deployable, testable learning system. These last blocks matter because financial correctness must survive packaging, credentials and recovery operations, not merely a successful happy-path request.

All25 planned functionality blocks are implemented. The next action is Docker-enabled full acceptance, especially real Redis and Linux-container behavior, then an explicit decision about scope extensions. No automatic Milestone26 is introduced. A live provider/bank, payouts, HA/TLS, managed secrets, cross-system disaster recovery and deletion policies are additional requirements rather than features implied by a finished learning roadmap. See the [final project map](FINAL_PROJECT_GUIDE.md) for concept groups and alternatives across all sessions.

## 12. Topics to learn from this session

- Reproducible Java artifacts and SHA-256 custody
- Immutable container image digests
- Non-root containers, namespaces and Compose lifecycle
- Secret generation, BCrypt verifiers and external key custody
- PostgreSQL advisory locks and atomic bootstrap
- Spring Security browser sessions and masked CSRF tokens
- Java HttpClient, Flow subscribers and complete-response deadlines
- Packaged acceptance tests and failure injection
- Bounded concurrency and latency percentiles
- CI artifact allowlists and skipped-test evidence
- Learning-project completion versus production readiness
