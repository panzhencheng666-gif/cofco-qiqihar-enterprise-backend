# News discovery incremental migration: CANDIDATE, NOT DEPLOYED

This directory is deliberately outside the default db/migration scan. V224 here
belongs ONLY to public.market_news_flyway_schema_history. It is unrelated to
risk V224 and must never be sent to the shared or risk Flyway history.

## Before production execution

1. Establish an approved existing secure database channel and verify TLS identity.
   Never reuse/re-enable qiliang_news_tmp_20260928. Do not request chat passwords.
   The workspace's initial NewsMigrationRunner.java is not an incremental runner.
2. Read actual news history and checksums: baseline 213 and successful
   218/219/220/221/223; preserve shared/risk history fingerprints. Unexpected state
   is a stop, not repair/baseline/manual history insertion.
3. Resolve the exact old five SQL files from the installed release and independently
   compare them to existing history. Put those unchanged files plus this V224 into
   an isolated migration location. They are for validation, NOT replay.
4. Configure defaultSchema=public, table=market_news_flyway_schema_history,
   baselineOnMigrate=false, cleanDisabled=true, outOfOrder=false, target=224,
   group=true, executeInTransaction=true, validation enabled. Require info.pending
   to contain exactly version224 and no other pending/failed/missing migrations.
   Use SET ROLE cofco_enterprise_migrator, lock_timeout2s, statement_timeout60s.
   These are reviewed requirements, not an already delivered executable runner.
5. Verify existing schema owner and runtime membership/USAGE, actual application
   database/role, table default ACLs and inherited grants. This SQL resets only
   PUBLIC and qiqihar_enterprise_runtime ACLs on its four NEW tables; grants to
   other roles inherited from owner default privileges are NOT cleared. Reject
   unexpected effective rights before enabling the feature.
6. Capture a NEW same-window recoverable backup and verify its readback; the
   morning backup is not sufficient. Isolate only affected writers as needed.
   Risk keeps writing: do not claim whole-database quiescence or use full restore.
7. Apply once through Flyway, validate/read back history, four table owners,
   constraints/indexes and effective runtime privileges. Check shared/risk
   histories unchanged. Require admission SELECT only; candidate/host/search
   SELECT INSERT UPDATE only, no grant option; no PUBLIC access.
8. If deployment fails after commit, disable discovery and retain its new tables
   and evidence while returning to the old application artifact. No destructive
   down migration/full-database restore is provided.

No admission records are seeded, no paid search is enabled, and no news item is
published by this migration. Runtime must not grant itself source permissions.
Source-admission evidence, an approved unexpired search budget/deadline, a fixed
private ledger and a single production scheduler still require deployment setup.
Expired temporary search authorization is not permission to renew it.

## Local evidence boundaries

NewsDiscoveryMigrationTest uses the protected loopback PostgreSQL test database,
a synthetic baseline223 and news_discovery_test_history. It validates SQL,
minimum table ACLs, no-op repeat, definition parity and conflict rejection.
It does NOT validate the real production 213+five-entry history, actual production
role inheritance, safe migration credentials, backups or public-browser results.
