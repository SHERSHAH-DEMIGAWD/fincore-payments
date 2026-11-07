# Milestone 24 — Logical backup, isolated restore and retention closure

**Continuation (28 October 2026):** [Milestone25](MILESTONE_25_LEARNING_GUIDE.md) now implements the final planned release/deployment/packaged acceptance block. Use the [project map](FINAL_PROJECT_GUIDE.md) and [final validation report](FINAL_VALIDATION_REPORT.md) for latest combined evidence and remaining Docker/Redis environment checks. The M24 results and proposed-next wording below remain its dated historical record.

Implemented 27 October 2026 in the independent `fincore-payments` learning backend. Continue from [Milestone 23](MILESTONE_23_LEARNING_GUIDE.md). Use the [recovery runbook](DATABASE_RECOVERY_RUNBOOK.md), [explicit retention decision](RETENTION_POLICY.md), [verification summary](MILESTONE_24_SUMMARY.md) and [roadmap](ROADMAP.md).

## 1. Why this block exists

M1–22 made accepted requests, financial journals and operational evidence durable in PostgreSQL. M23 made stalled work visible. Durability on one running database still leaves an unanswered question: can a separate reconstruction preserve those exact relationships and application behaviors?

M14/M15 preservation copies contain selected retired-projection artifacts. Their offline verifier establishes agreement with trusted reference files. That is useful, but it does not reconstruct payment rows, retry responses, journals, merchant identities and later provider/risk evidence together. A successful file hash alone also does not prove a database can load the archive or that restored guards still work.

M24 adds a bounded logical database backup with a consistent source fingerprint and a separate read-only verifier for an isolated restore. A native drill actually runs PostgreSQL dump/restore tools, starts the recovered API and exercises historical retries/ownership/accounting. A written retention decision retains authoritative data and grants no deletion capability. No financial schema migration is needed; V22 remains current.

## 2. What a backup, manifest and restore report each establish

| Artifact | Implemented role | What it does not establish |
| --- | --- | --- |
| `database.dump` | PostgreSQL custom-format logical database archive | Encryption, trusted provenance, external keys or cluster-role recovery |
| `manifest.json` | Versioned bundle identity, archive length/hash, source identity hash, schema fingerprint and per-table counts/digests | Authenticity without separately trusted custody of its hash |
| Capture JSON | Successful completed capture and trusted manifest/archive hashes | Successful restore |
| Restore verification JSON | One read-only comparison of an isolated database with the trusted manifest/archive | Full platform cutover, future integrity, signed attestation or deletion authority |

SHA-256 detects disagreement with an expected digest. It is not an HMAC or signature. If an attacker replaces both the bundle and its only reference, a matching new set is not proof of authenticity. Keep the capture's manifest hash through a separate trusted process, along with the exact application artifact and required external resources.

Logical backup reconstructs database objects and data through SQL/COPY. It differs from a physical cluster backup and continuous WAL archive. This choice makes a small learning-system drill inspectable; it does not implement point-in-time recovery, role/tablespace restoration or a zero-loss production recovery plan. [PostgreSQL pg_dump](https://www.postgresql.org/docs/18/app-pgdump.html).

## 3. Capture flow and the exported snapshot

```text
java -jar ... create-recovery-backup --directory=NEW --pg-dump=ABSOLUTE_BINARY
→ FincorePaymentsApplication.main [headless dispatch]
→ DatabaseRecoveryCli.run → DatabaseRecovery.Target.connect
→ PostgreSQL read-only Repeatable Read transaction
→ DatabaseRecovery.inventory [source schema + rows + journal check]
→ pg_export_snapshot()
→ DatabaseRecovery.dump → pg_dump --snapshot=... --format=custom
→ archive hash → completed manifest → capture JSON
```

The inventory and dump must represent the same database image. Otherwise a payment committed between “count source rows” and “run backup” could make a valid dump look corrupt, or make an incomplete comparison appear correct.

The source transaction stays open while the child `pg_dump` imports its exported snapshot. A native test commits a new merchant after the source fingerprint is taken but before the dump starts. The live source contains that merchant; the restored archive does not, and its fingerprint still matches. This tests a real concurrent-commit boundary rather than assuming two sequential operations are equivalent. [Snapshot synchronization](https://www.postgresql.org/docs/18/functions-admin.html#FUNCTIONS-SNAPSHOT-SYNCHRONIZATION).

The command writes no source rows. It does hold Access Share table locks and an MVCC snapshot. Readers/writers can normally continue, but table-changing DDL can wait and old row versions can remain needed. Coordinate a migration-free capture window; a read-only backup is not free of operating cost.

## 4. What the fingerprint covers

`DatabaseRecovery.inventory` enumerates every supported public table, including the Flyway history, financial and operational tables. Each table is read as PostgreSQL `to_jsonb(row)::text`, sorted under C collation. A domain prefix and byte-length prefix for every UTF-8 row feed a SHA-256 digest. A count accompanies that digest.

The length prefixes distinguish different row boundaries; the domain prefix distinguishes tables/format versions. Sorting makes physical row order irrelevant. PostgreSQL JSONB serialization orders object keys; explicit UTC/date/interval/bytea/float settings stabilize session-sensitive values. Stored JSON/text payloads remain part of their row value rather than being replaced with DTOs. Fingerprints therefore detect changed values even when row counts match.

Structural evidence includes database encoding/collation, table properties, ordered columns/types/nullability/defaults, constraints, indexes/validity, public function definitions, triggers/enabled state, rules, policies and supported extension metadata. A missing index or disabled guard cannot pass merely because financial rows still match. Ownership/ACLs, every possible catalog object and external dependencies are not a general-purpose schema certification.

Before capture and verification, an aggregate journal check requires each header to have one matching DEBIT and CREDIT of the header's amount/currency. This covers the existing separate principal/fee journal pattern. It supplements exact source agreement; it is not a replacement for all domain invariants, independent provider evidence or historical-completeness analysis.

## 5. A real restore exposed a subtle schema issue

PostgreSQL can print equivalent expressions differently after dumping and reparsing them. The first real restore had identical supported schema intent but displayed these array casts differently:

```sql
-- Source definition
ARRAY['ALLOW'::character varying, 'DENY'::character varying]::text[]
-- Reparsed definition
ARRAY['ALLOW'::character varying::text, 'DENY'::character varying::text]
```

Hashing raw deparsed text rejected that legitimate restore. Broadly stripping whitespace/casts would risk hiding real differences, including truncating `varchar(n)` casts or changes inside quoted text.

`RecoverySchema` handles only this narrow case: arrays of ordinary string literals with **unbounded** `character varying`, whose final type is explicitly text/text[]. It preserves the literal values/order, operators and surrounding definition. It skips quoted identifiers, standard/E strings, comments and dollar-quoted content; it never normalizes function bodies. Bounded varchar, other types and unsupported forms remain unchanged and can still cause a conservative refusal. [PostgreSQL character types](https://www.postgresql.org/docs/18/datatype-character.html).

Four unit cases establish the accepted equivalence and protect values, quoting and unsupported types. Native missing-index/disabled-trigger checks establish that structural checking still rejects meaningful differences. This is a format-specific compatibility rule, not a general SQL equivalence engine or an instruction to “repair” a failed manifest.

## 6. Restore and comparison flow

```text
Trusted manifest hash → verify manifest bytes → verify archive hash/length
→ operator creates NEW fincore_restore_<random> database
→ pg_restore --single-transaction --exit-on-error --no-owner --no-privileges
→ java -jar ... verify-recovery-restore
→ DatabaseRecoveryCli.readManifest → DatabaseRecovery.inventory [read-only target]
→ exact inventory comparison → new verification report
```

Restore SQL executes through trusted PostgreSQL tooling as an explicit operator step. The Java commands never execute a restore, drop a database or overwrite source data. The runbook creates a random new database and supplies no `--clean`/`--create` restore option. The verifier additionally requires a loopback target named `fincore_restore_...` and a different source endpoint identity hash.

`--single-transaction` plus exit-on-error makes the restore command fail coherently rather than reporting success after ignored SQL errors. The newly created database remains available for investigation after failure. A dump can execute SQL chosen by its source; verify custody before restoring, not only afterward. [PostgreSQL pg_restore](https://www.postgresql.org/docs/18/app-pgrestore.html).

The verifier rechecks the trusted manifest/archive, uses read-only Repeatable Read and compares all supported rows/schema. Only agreement writes a new report with evidence UUID, bound artifact hashes and `restoredIdentitySha256`, identifying the configured target endpoint without disclosing its address. That hash is not a server attestation. Existing reports are refused. The report records `restoredDatabaseReadOnly=true`, `externalResourcesRecovered=false` and `deletionPermitted=false`; these fields keep the proof's scope explicit.

## 7. Safe operating boundaries and budgets

Both commands run before Spring startup. They create no HTTP server, consumer, Redis client, dispatcher or trace exporter. Packaged tests inherit deliberately invalid enabled application settings and still return headless results. Recovery credentials come only from dedicated environment variables; fixed public error JSON omits passwords, paths, payloads and exception details. The target's diagnostic string is redacted.

The current contract accepts a single explicit `127.0.0.1:port/database` JDBC endpoint, PostgreSQL17/18, UTF-8 and the V1–22 migration history. Same-major comparison is intentional. The scope rejects additional user schemas, sequences/views/partitions/foreign/unlogged tables, large objects, custom public types/aggregates, RLS, extra extensions, unvalidated constraints and disabled triggers. It fails rather than silently treating an unsupported object as covered.

Budgets are 128 tables, one million rows/256MiB of serialized table data, 512MiB of completed archive and a 1MiB manifest. Queries have 30-second statement timeouts and five-second lock waits; the dump child has 120 seconds. Rows are fetched in small batches. Sorting can still use database resources, a row must still be materialized, and an archive can grow before its post-dump size check. These settings are not a total resource or end-to-end deadline guarantee.

Files are sensitive and unencrypted; `recovery-artifacts/` is ignored by Git. A failed capture may leave partial files without a completed manifest. There is no automatic deletion/retry or repair. A generic refusal intentionally sends the trusted operator to protected local logs/configuration instead of leaking diagnostics publicly.

## 8. What the restored API drill proves

The native test first provisions its own fresh source database and migrates/seeds it through the application. Older regression tests retain upgrade schemas in their shared database; the recovery contract correctly refuses those extra schemas, so the drill isolates its fixture rather than deleting their evidence or relaxing scope. It restores into fresh generated databases, verifies through the shipped JAR and starts the recovered API with external side effects disabled. It then uses actual Basic authentication and genuine CSRF to retry an original payment creation.

The accepted creation snapshot still says CREATED, even though a current GET says REFUNDED. That is M2's historical retry contract surviving backup/restore. A different merchant still receives 404. Another payment's partial-capture accounting still reports principal40.00 and fee2.00. A forbidden journal update still fails. After stopping the API, another verification confirms rows still match; a source fingerprint confirms that inspection did not alter the live source.

Other tests change a restored row without changing its row count, remove retry rows, drop an index, disable guards, damage the archive and substitute the trusted hash. They also check partial backup failure, source-as-target refusal and overwrite refusal. None “fixes” mismatches to obtain a green report.

This is independent logical reconstruction into new databases on the same PostgreSQL host. It is not a separate-host loss simulation, an external-provider recovery, a public deployment or a measured recovery-time guarantee.

## 9. Retention and the external-resource boundary

The [retention decision](RETENTION_POLICY.md) retains authoritative data and keeps physical deletion disabled. A successful restore does not make old payment keys, financial journals, receipt histories or approvals disposable. The source relationships still govern retries, ownership and audit interpretation.

PostgreSQL is only one resource. Restoring encrypted webhook key bytes without the master key does not restore signing capability. Restoring provider command rows without the provider journal/instance identity cannot recreate external decisions. Kafka state/configuration and consumer context need their own recovery review. Redis loss can replenish soft quota; monitoring history is disposable diagnostic evidence rather than financial authority.

The report always marks external resources unrecovered. Operators must preserve keys/key IDs, stable sandbox identity/journal, application versions and trusted reference custody independently, and review uncertain/leased work before enabling delivery. There is no distributed snapshot across those systems, automatic failover, purge executor or automatic retention scheduler.

## 10. Choices, alternatives and costs

| Chosen approach | Alternative | Why selected / accepted cost |
| --- | --- | --- |
| PostgreSQL custom-format logical archive | Physical base backup plus WAL/PITR | Inspectable small local drill and portable SQL reconstruction; no point-in-time recovery/cluster globals |
| Shared exported snapshot | Fingerprint and dump at unrelated times; stop all writers | Consistent evidence while ordinary writes continue; long read transactions need capacity/DDL coordination |
| Whole supported table digests plus structural evidence | Row counts alone; checking only sampled payments | Detects changed/missing data and missing guards; full scans/sorts impose explicit teaching limits |
| Separately trusted manifest hash | Trust a self-described bundle; introduce a signing PKI now | Simple explicit provenance boundary; no automated authenticity/attestation service |
| Fresh isolated target and read-only comparison | Restore over an operational database | Inspectable reversible drill without source replacement; operator must separately decide any cutover |
| Narrow schema normalization | Strip all casts/whitespace; ignore schema differences | Accommodates observed PostgreSQL reparse behavior while retaining meaningful differences; unsupported forms may refuse |
| Retain authoritative records | Automatically purge old records after a successful copy | Preserves retry/audit dependencies until a dedicated reviewed deletion contract exists; storage continues growing |

These alternatives have valid uses in larger systems. This project has not measured production backup windows, storage durability, hard RPO/RTO or deletion requirements; those decisions must follow actual operating needs and evidence.

## 11. Verification, holistic impact and next work

The broad Java21 Maven clean verify completed at **2026-10-27T23:52:18+05:30** with **1,243 passes**, zero failures/errors and **454 Docker skips**: 748 unit/MVC/configuration/CLI, 481 native PostgreSQL and 14 packaged CLI/sandbox checks. Final report target-identity metadata was then verified by **27 overlapping M24 passes** at **2026-10-27T23:54:16+05:30**, including actual pg_dump/pg_restore and recovered HTTP. Latest per-suite coverage remains 1,243; this is broad coverage plus final focused verification rather than a second full run against final source. Interrupted/focused passes are not double-counted. The [summary](MILESTONE_24_SUMMARY.md) records evidence and cleanup.

Actual local tooling is Windows, Java21 and PostgreSQL18.6, with Flyway's warning that PG18 is newer than its tested support range. PostgreSQL17/Linux/Docker, a second host, off-site custody, physical/WAL recovery, external resource restoration, representative load and live funds remain outside this verification. Earlier real Redis/native Kafka environment gaps are tracked separately. No new migration is introduced.

M1–23 established correctness, evidence and visibility. M24 now demonstrates that a supported database image can be reconstructed separately and still serve key protected financial behaviors. The retained evidence is stronger than “a backup command exited successfully,” while its scope remains narrower than a fully recovered running payment platform.

**Next: M25 — reproducible deployment and final validation.** Consolidate deployment/configuration, an end-to-end sandbox demo, remaining failure/load/security checks and honest final documentation. One planned milestone remains. Optional live-provider, production IAM, cross-system disaster recovery and physical deletion are not silently added to the learning-project finish line.

## 12. Source reading order

1. `FincorePaymentsApplication.main` — explicit headless command dispatch.
2. `operations/DatabaseRecoveryCli.java` — strict options, manifest trust, completion/report boundaries and sanitized output.
3. `operations/DatabaseRecovery.java` — read-only sessions, inventory, journal check, hashing and snapshot-aware dump subprocess.
4. `operations/RecoverySchema.java` — narrow dump/reparse normalization.
5. `operations/DatabaseRecoveryCliTest.java`, `RecoverySchemaTest.java` — configuration/file/format and normalization behavior.
6. `operations/LocalPostgresRecoveryIT.java`, `PackagedDatabaseRecoveryIT.java` — real reconstruction, recovered HTTP and shipped entry points.
7. Recovery runbook and retention decision — executable sequence and explicit operating obligations.

## TOPICS TO LEARN FROM THIS SESSION

- Logical versus physical database backups
- PostgreSQL exported snapshots and MVCC
- Repeatable Read and read-only transactions
- Canonical serialization and streaming fingerprints
- SHA-256, trusted references and authenticity
- PostgreSQL schema deparsing and type casts
- Restore transactions and isolated recovery drills
- Idempotency and tenant isolation after restore
- Key custody and cross-system recovery boundaries
- Retention decisions and deletion authorization
- RPO, RTO and point-in-time recovery
