# Milestone 24 — Database recovery and operational closure

Implemented 2026-10-27. [Learning guide](MILESTONE_24_LEARNING_GUIDE.md) · [Runbook](DATABASE_RECOVERY_RUNBOOK.md) · [Retention decision](RETENTION_POLICY.md) · [Roadmap](ROADMAP.md).

## 1. What was implemented

- Headless logical PostgreSQL backup with a manifest from the same exported snapshot.
- Archive integrity, per-table row fingerprints and structural/journal checks.
- Read-only isolated-restore verification against a separately trusted manifest hash.
- New local verification reports with explicit external-resource/deletion limits.
- Strict supported-scope checks and overwrite refusal for backup/evidence files.
- Explicit authoritative-data retention decision and reproducible recovery instructions.

## 2. Concepts / technologies used

- PostgreSQL pg_dump/pg_restore — logical reconstruction into new databases.
- MVCC / exported snapshots / Repeatable Read — consistent source evidence.
- JDBC / read-only transactions — independent headless inventory and verification.
- SHA-256 / canonical serialization — archive and row/schema comparison.
- Strict JSON / bounded subprocesses / environment credentials — recovery command contracts.
- JUnit / real HTTP / packaged-JAR tests — reconstruction and preserved application behavior.

## 3. Important files

- `FincorePaymentsApplication.java` — dispatches both commands before Spring.
- `operations/DatabaseRecoveryCli.java` — capture/verification options, trusted manifest and report contract.
- `operations/DatabaseRecovery.java` — snapshot inventory, hashing, journal checks and pg_dump execution.
- `operations/RecoverySchema.java` — narrow equivalent-array-cast normalization.
- Recovery unit/native/packaged tests — integrity/refusal and actual restored API checks.
- `DATABASE_RECOVERY_RUNBOOK.md`, `RETENTION_POLICY.md`, learning guide — recovery, retention, concepts and handoff.

## 4. Request flow

```text
create-recovery-backup → FincorePaymentsApplication.main → DatabaseRecoveryCli.run
→ DatabaseRecovery.inventory → pg_export_snapshot → DatabaseRecovery.dump
→ pg_dump → archive + manifest
→ operator pg_restore into NEW fincore_restore_* database
→ verify-recovery-restore → DatabaseRecovery.inventory → new verification report
```

## 5. Important design decisions

- Source fingerprint and dump share one exported read-only snapshot.
- Verifier checks exact trusted manifest/archive bytes and all supported table fingerprints.
- Schema comparison normalizes only the observed unbounded-varchar-to-text literal-array equivalence.
- Restoration is an explicit operator step into a new database; Java commands never execute destructive SQL.
- No Spring workers start in the recovery commands; credentials are environment-only.
- Reports remain unsigned local observations with external resources unrecovered and deletion disabled.
- Current V22 schema and bounded same-major local logical recovery are explicit scope.
- Authoritative records remain retained; no purge/expiry executor is added.

## 6. Test status

The broad Java21 Maven **clean verify** completed **BUILD SUCCESS** at **2026-10-27T23:52:18+05:30**, in 16:43 min: **1,243 executed passes**, zero failures/errors. A final focused verify passed **27 overlapping M24 checks** at **2026-10-27T23:54:16+05:30** after adding the configured target identity hash to reports; its native assertion verifies that binding. Latest per-suite coverage remains 1,243 passes, not 1,270, and is not a second full run against final source.

| Suite | Passed | Skipped |
| --- | --- | --- |
| Unit / MVC / configuration / CLI | 748 | 0 |
| Real PostgreSQL | 481 | 0 |
| Packaged CLI / sandbox | 14 | 0 |
| Docker-dependent | 0 | 454 |

The broad run includes real authenticated Prometheus scraping from M23 as well as M24's actual PostgreSQL dump/restore and recovered HTTP behavior. No new migration: V1–22 checksums match M23. The final focused run's packaged JAR covers final application source/resources. Ignored evidence is `.tools/milestone24-verify.log`, `milestone24-final-focused.log`, checksum files, QA/cleanup JSON and PG log, plus native `target/recovery-drill-*` artifacts. Four final focused reports independently bind their retained manifest/archive hashes and target identity; deliberately damaged negative-test artifacts remain distinct.

The earlier broad attempt was interrupted after confirming older migration tests retain extra schemas. Recovery fixtures now provision a separate fresh source DB, preserving the strict supported-scope refusal and existing evidence. Sandbox process inspection initially failed; the attempted restart did not launch because the original log was still open. Verified workspace JVMs were then stopped with approved inspection, and the final broad run started cleanly. The interrupted log is retained and excluded from counts.

Workspace PostgreSQL55432 was stopped cleanly at 2026-10-27T23:56:13.6543506+05:30, with no PID file/responding55432 server or workspace test/dump/restore child. Source/restore databases and logs are retained. No unrelated5432/project or commit/push changed.

M24 adds 27 checks: 17 unit, eight native PostgreSQL recovery scenarios and two packaged headless checks. Focused passes overlap the broad coverage and are not added again.

Coverage includes a real concurrent source commit excluded by the shared snapshot, actual custom-format dump/restore, changed/missing rows, damaged archive/wrong reference, missing index/disabled guards, partial backup failure, overwrite refusal and source-as-target refusal. The shipped commands work despite deliberately invalid enabled Spring settings. Actual recovered HTTP verifies original creation retry, current refunded state, foreign-merchant404, principal40.00/fee2.00 and immutable journal protection; verification/source fingerprints confirm inspection preserves database rows.

The first focused attempt found an ambiguous PostgreSQL internal-char concatenation. The next exposed legitimate dump/reparse array-cast differences. Explicit casts and narrow tested schema normalization corrected these before the passing focused run; financial and mismatch assertions remain.

Actual environment: Windows / Java21 / PostgreSQL18.6; Flyway reports its PG18 support warning. No new migration; V22 remains current. PostgreSQL17/Linux/Docker, a second host, off-site custody, WAL/PITR, external keys/provider/Kafka restoration, load and live funds remain unverified. Earlier real Redis validation remains outstanding. Interactive PowerShell instructions are syntax-checked separately from the automated native drill.

## 7. Current project status

**COMPLETED:** ✓ Milestones 1–24 functionality, including supported logical database reconstruction and explicit retention closure.

**CURRENTLY POSSIBLE:** ✓ Capture consistent supported DB evidence, reconstruct a fresh isolated database, verify integrity and exercise protected recovered payment behavior.

**NOT IMPLEMENTED YET:** ✗ M25 deployment/final validation; production disaster recovery, cross-system recovery, independent off-site custody, signed attestations and physical purge remain additional scope.

**NEXT LOGICAL MILESTONE:** M25 — reproducible deployment and final sandbox/failure/load/security validation. One planned milestone remains, without a production-readiness claim.

## 8. TOPICS TO LEARN FROM THIS SESSION

- Logical and physical backups
- Exported snapshots and MVCC
- Read-only Repeatable Read
- Canonical fingerprints and trusted hashes
- Schema deparsing and safe normalization
- Isolated restore testing
- Idempotency after restore
- Key custody and recovery scope
- Retention, RPO, RTO and PITR
