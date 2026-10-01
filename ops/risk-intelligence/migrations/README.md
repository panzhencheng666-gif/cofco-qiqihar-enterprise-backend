# Independent risk migration history

These SQL files belong to `public.risk_flyway_schema_history`, baseline213. They
are not discovered by the main application's `classpath:db/migration`. The
cloud bundle explicitly combines shared source V214–V217 with this directory.
Risk V218–V225 keep their original filenames and bytes; shared market migrations
with the same version numbers remain untouched in the main migration directory.

V217.1 preserves the bootstrap UUID and lineage before a fresh QL identity is
created. It permits only a disabled, unused bootstrap DRAFT to be suspended and
retains all other identity/lifecycle checks. Existing V218+ installations apply
this one known earlier migration with a guarded out-of-order check that rejects
other missing legacy versions. V226 requires manual promotion for both seeds and
the actual QL model identity, without changing earlier applied checksums.

The runner permits the exact current shared market/seed migration scripts and
rejects independent risk ownership in shared history. Two disposable PostgreSQL
upgrade tests cover a shared guarded V217 installation and an existing V225
installation. This source integration does not deploy or certify model quality.
