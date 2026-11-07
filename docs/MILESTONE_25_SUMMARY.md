# Milestone25 — deployment and final validation

28 October 2026. [Learning guide](MILESTONE_25_LEARNING_GUIDE.md) · [Deployment runbook](DEPLOYMENT_RUNBOOK.md) · [Project map](FINAL_PROJECT_GUIDE.md) · [Final evidence](FINAL_VALIDATION_REPORT.md).

## 1. WHAT WAS IMPLEMENTED

- Generated private demo credentials/keys and atomic, retry-safe two-merchant bootstrap.
- Built verified release bundles bound to the exact tested JAR by SHA-256.
- Added a digest-pinned local Compose sandbox with non-root Java processes and retained volumes.
- Added authenticated packaged acceptance for provider uncertainty/callback recovery, settlement, accounting and notification retry.
- Added bounded concurrent creation, tenant/CSRF checks and private telemetry/projection checks.
- Supplied a Docker-enabled CI recipe and consolidated operating/learning documentation.

## 2. CONCEPTS / TECHNOLOGIES USED

- SHA-256/Maven output timestamps — artifact identity/reproducibility.
- Docker Compose/image digests — isolated pinned deployment inputs.
- SecureRandom/BCrypt/PostgreSQL advisory locks — bootstrap credentials and coordination.
- Java HttpClient/Flow/CSRF sessions — bounded authenticated acceptance.
- GitHub Actions — verification and combined container acceptance recipe.

## 3. IMPORTANT FILES

- `LocalSandboxCli.java` — initialization, bootstrap and private readiness commands.
- `SandboxAcceptanceCli.java` — real HTTP/security/failure/concurrency exercise.
- `SandboxHttp.java` — complete-response deadlines and byte limits.
- `Dockerfile`, `deployment/compose.yml`, `deployment/entrypoint.sh` — pinned local runtime and secret loading.
- `scripts/Build-LocalRelease.ps1`, `Verify-LocalRelease.ps1` — tested bundle creation and integrity checks.
- `docs/FINAL_PROJECT_GUIDE.md`, `DEPLOYMENT_RUNBOOK.md` — holistic learning map and operating instructions.
- Repository-root `.github/workflows/fincore-payments.yml` — Docker-enabled CI recipe.

## 4. REQUEST FLOW

`FincorePaymentsApplication.main → SandboxAcceptanceCli.run → Exercise.execute → PaymentController.createPayment → MerchantPaymentService.createPayment → PaymentService.createPayment → PostgreSQL → PaymentResponse`.

## 5. IMPORTANT DESIGN DECISIONS

- Deploy the verified JAR; verify its digest again during image build.
- Preserve literal-loopback adapters by sharing the API network namespace.
- Give concurrent browser workers independent cookies and fresh masked CSRF tokens.
- Retain credentials, provider journals, database and receiver evidence across reruns.
- Report bounded smoke timings and skipped validation without claiming production capacity.

## 6. TEST STATUS

Fresh Java21 clean verify: **1,269 passed** (761 unit/MVC/configuration/CLI,483 native PostgreSQL,7 real Kafka,18 packaged CLI), **zero failures/errors**, **454 Docker-dependent skips**. Shell/verifier/Compose/workflow checks passed separately. Two clean same-toolchain builds produced identical JAR bytes. Actual Docker image execution, real Redis and Linux remain untested here.

## 7. CURRENT PROJECT STATUS

COMPLETED: ✓ All 25 planned functionality blocks, including the local release/acceptance implementation.

CURRENTLY POSSIBLE: ✓ Verified packaged native payment/provider/callback/accounting/settlement/notification/Kafka demonstration.

NOT IMPLEMENTED YET: ✗ Live payments/payouts, HA/TLS, managed secrets, full cross-system recovery and physical deletion.

NEXT LOGICAL MILESTONE: No automatic Milestone 26. Run the supplied combined Docker/Redis acceptance on a suitable machine, then agree any scope extension.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Artifact integrity and reproducible builds
- Image digests and non-root containers
- Compose namespaces and lifecycle
- Secret custody and atomic bootstrap
- CSRF sessions and authenticated clients
- Complete-response deadlines
- Failure injection and bounded concurrency
- CI artifacts and verification boundaries
