# Login availability and heap recovery, 2026-09-24

Production: `container-cofco-cloud-oidc-backend-20260910.service`, port 19091.

## Incident evidence

The 512 MiB JVM reported `java.lang.OutOfMemoryError: Java heap space` in
`RegionalHierarchyRefresh.refreshRegions` and Tomcat's NIO Poller. The JVM
remained alive with its HTTP poller unavailable; localhost health and session
requests timed out. Database activity at diagnosis was predominantly idle.
The refresh retained 2,601 full profiles. Persisted calculation payloads totalled
275 MB as JSON (28 MB compressed in PostgreSQL), excluding other profile fields
and live application memory. This retention was an identified source of heap
pressure, not proof that no other memory issue can exist.

## Required runtime settings

Keep `-Xmx2g -XX:+ExitOnOutOfMemoryError` in the existing `JAVA_TOOL_OPTIONS`
line of `/var/lib/cofco/identity-20260910/backend.env`, preserving the existing
truststore options. Do not increase thread stack size. The observed host has
about 15 GiB RAM, and there was sufficient available memory for this heap.
`Restart=on-failure` in the existing service handles an OOM-triggered JVM exit.
Do not deliberately trigger OOM in production to test this mechanism.

The source change bounds the refresh's ancestor-reuse cache to 32 full profiles.
Evicted ancestors are recomputed; region processing and persistence remain in
place. Both manually created Hikari pools now set driver connection/read/cancel
timeouts as well as connection retirement and idle settings. Hikari borrow
timeout and maxLifetime do not interrupt an in-use JDBC socket read.

## Guarded deployment evidence

- Original JAR SHA-256: `7ff1b3dc811a225d4258946b62a1e40a4b956163e7140261f0682f58b7f9997a`.
- Applied JAR SHA-256: `ee1c69193a6e470c435520da1c91255efc76662051f1a9fb2caf7df193e737af`.
- Patch ZIP SHA-256: `ba07b9838449cabef66d55ee1a5c14d0f05055e6e1388ff48b85be298557ff8b`.
- Backups: `/var/lib/cofco/releases/login-oom-recovery-20260924T100045/`.
- Apply invocation: `t-bj06xznfm13vpj4`; completed check, apply, health and class readback.
- Only SessionPoolConfiguration, RegionalHierarchyRefresh and its new anonymous
  cache class changed; every unrelated JAR entry was compared byte-for-byte.
- A first apply encountered Python 3.6's unsupported `capture_output` argument,
  restored the original JAR and restarted. The compatible rerun independently
  rechecked the original JAR and class hashes before applying successfully.

The backup includes the original environment and JAR. Rollback of this code
change should retain the 2 GiB heap and ExitOnOutOfMemoryError protection unless
those settings themselves have been diagnosed as a problem. Never overwrite
a newer JAR without checking its current hash and concurrent releases.

## Validation and limits

- Five focused tests passed against an exclusive local PostgreSQL database:
  real pool configuration, business/session connection isolation, 20 concurrent
  session queries with the business pool full, actual JDBC socket timeout and
  pool recovery, and processing all 2,601 regions with at most 32 cached profiles.
- Production: 100 probes at concurrency 10; 50 health requests returned 200 and
  50 anonymous session requests returned 401, maximum 0.036 seconds.
- No actual OOM exception in post-restart logs at the recorded check. Do not
  count the startup string `ExitOnOutOfMemoryError` as an OOM event.
- Real browser acceptance after final apply: admin session, corn market form,
  100 records and working first-page display. No console errors were captured.
- These are bounded recovery/regression checks, not a multi-account login load
  test or a full-day background-refresh soak. Never claim permanent immunity
  from all future out-of-memory conditions from this evidence.

Future releases must include these source changes and preserve the runtime heap
settings; rebuilding from an older branch can reintroduce the problem. This
patch is based on the already deployed session-pool guard source at 8d35e7a.
