# Validation status

This document separates implemented functionality from completed execution evidence. All 25 planned functionality milestones are implemented; the platform uses sandbox funds and is not certified for production.

## Completed local evidence

- The final local Java 21 clean verification on 28 October 2026 recorded **1,269 passes**, zero failures/errors: 761 unit tests, 483 native PostgreSQL tests, seven native Kafka tests and 18 packaged checks.
- That run also recorded **454 Docker-dependent skipped methods/templates**. It did not exercise real Redis or the complete Linux container deployment. Parameterized templates expand to **484 container test cases** when executed.
- Native packaged acceptance exercised real Kafka and explicitly reported risk controls disabled. It does not establish Redis-backed acceptance.
- On 30 October, 14 synthetic validation-runner fixtures passed. A separate preflight correctly refused to run without Docker. These checks add no application-test passes.

Historical milestone reports retain their original dates and scope. Ignored local evidence and private host repair records are not included in this repository.

## GitHub Actions validation

The [container validation workflow](https://github.com/SHERSHAH-DEMIGAWD/fincore-payments/actions/workflows/fincore-payments.yml) runs on an Ubuntu runner with Java 21, Linux Docker and a checksum-verified Prometheus binary. It invokes `scripts/Run-ContainerValidation.ps1`, which requires:

1. Fresh Maven/JUnit reports with at least **484 executed container cases across 24 suites**, with zero skips, failures or errors.
2. Packaged acceptance using the exact tested JAR in an isolated Compose project.
3. `LOCAL_SANDBOX_ACCEPTANCE_PASSED`, `riskValidated=true`, `kafkaProjectionValidated=true` and `realFunds=false`.

The workflow uploads only the validation summary and, when available, the acceptance report. Generated credentials, runtime directories, database contents and raw logs are excluded from uploaded artifacts. Check the actual run and its evidence before treating container validation as complete.

## Run locally

With Java 21, a Linux Docker engine, PowerShell 7 and a real Prometheus binary available:

```powershell
pwsh -File scripts/Run-ContainerValidation.ps1 -PrometheusBinary /path/to/prometheus
```

Set `JAVA_HOME` or supply `-JavaHome`. The Maven wrapper downloads Maven and dependencies on first use; `-Offline` is only suitable once dependencies are cached. Docker image pulls still need network access. Every attempt creates a new ignored `release-artifacts/container-validation-*` directory. Cleanup stops its own Compose project and retains volumes.

## Remaining scope limits

Passing container acceptance would establish the supplied local sandbox scenario. It would not establish live payment-provider integration, real bank payouts, production-scale performance, off-site disaster recovery or a production security assessment.
