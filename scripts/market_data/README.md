# Choice subscription and normalization (candidate only)

`choice_subscription.py` is an isolated lifecycle component for the official
Choice Python SDK. It is not installed or started by the application. It does
not import the SDK, read credentials, publish prices or infer contract codes.

## Before connecting

- Confirm supplier entitlement for the selected exchanges and internal display.
- Activate the official SDK in a private, dedicated process directory; keep
  tokens outside source control. Use **one session per process**, since the SDK
  has global login and callback state. Never share it with other SDK consumers.
- Supply verified explicit codes and fields. Defaults `TIME,NOW` and
  `Pushtype=2` come from the vendor's example, not a futures acceptance result.
- Pass `authorized=True` only after checking actual supplier permission. This
  local flag does not grant that permission. The default makes no SDK calls.

## Ownership and integration contract

The caller injects the official SDK `c`, calls `start()` once, consumes `take()`
on its own worker thread, and **always calls `close()` in a finally block**.
Instances cannot be restarted; close the old one before creating a replacement.
There is no forced login and no automatic application-level login retry.

`status()` contains sanitized state and numeric supplier error codes. In
`SESSION_LOST`, `ENTITLEMENT_ERROR` or `SOURCE_ERROR`, the supervisor must call
`close()` outside SDK callback threads. Such states clear queued data and reject
later callbacks. Calls to the SDK from its callbacks could deadlock, so cleanup
is deliberately owned by the supervisor. A failed `start()` cleans up any
successfully acquired login/subscription before returning.

`RECONNECTING` reflects the SDK's own reconnect notifications. It does not start
another login or duplicate subscription. `RECEIVING` only means a raw batch
arrived, not that its prices are current, correctly mapped, or licensed for a
particular display. A queue holds at most 256 frames by default; overflow drops
the oldest frame and increments `droppedFrames`. The future supervisor should
request a validated snapshot/reconcile state after loss. `take()` never blocks.

Raw `Codes`, `Indicators`, `Dates`, `Data` are copied into each batch. The next
adapter layer must validate requested codes, indicator dimensions, units,
source date/time/timezone (including night sessions), freshness and ordering.
It must not substitute local receipt time for `sourceAt`. No quote may reach
`MarketQuoteGateway` until the complete integration has been validated.

`cleanupError` reports cancellation/logout failure without vendor error text;
the dedicated process must be treated as unhealthy if cleanup fails. This module
does not supervise or restart operating-system processes.

## Verification and sources

### Quote normalization boundary

`choice_quotes.py` provides `QuoteNormalizer(catalogue, bindings)` with explicit
system-ID/unit catalogue entries and verified `{code, id, unit, verified: true}`
bindings. No real supplier codes are shipped. The verification flag is caller
evidence, not proof of vendor entitlement or a code-discovery mechanism.

`ingest(frame)` validates dimensions and indicator names, accepts positive finite
numeric prices, and requires a full ISO source timestamp with an explicit offset.
**Actual futures SDK TIME format/date/timezone remains unverified**. Time-only
values are rejected; SDK `Dates` and local receipt time are never substituted.
A verified vendor timestamp resolver is required if the SDK returns another
format. `PRECLOSE` is optional and must be requested and confirmed against the
vendor; futures settlement-based change cannot be inferred from this field.

Partial updates retain other instruments. Older timestamps, duplicates and
conflicting values at the same timestamp are rejected with separate counters.
There is no sequence-number support yet; feeds with several ticks at the same
timestamp need a verified ordering mechanism before enabling this conservative
policy. Future timestamps beyond 60 seconds are rejected. `snapshot()` returns
detached gateway-shaped quote records and marks source timestamps older than
90 seconds STALE. This intraday threshold does not infer exchange session state
or imply continuous trading. CURRENT only describes age, not supplier permission.

Mappings require identical units; unit conversion, dominant-contract selection,
rollover, HTTP serving and dashboard wiring
remain separate integration work. A process restart currently loses this cache.
Neither module starts itself or supplies production prices.

### Recovery coordinator

`choice_recovery.py` adds `ChoiceRecovery(subscription, normalizer_factory)`.
The factory must return a **fresh** normalizer with the same verified bindings
on every call. Start the subscription first, call `step()` on one supervisor
worker, and use `view()` for quotes **together with** transport/recovery state.
Always close the subscription in the supervisor's finally block.

Initial startup, a disconnect, or queue overflow requires a complete snapshot.
The transport records a monotonic `lossGeneration`, so a disconnect immediately
followed by a tick cannot hide the loss. `request_snapshot()` calls the official
`csqsnapshot` with the subscription's exact codes/fields outside SDK callbacks.
It serializes with start/close and discards results if another loss happened
during the call. It never performs a forced login or application login retry.

Each worker step drains at most 256 frames and makes at most one snapshot call;
recovery requests are separated by at least five seconds after completion.
Incomplete, invalid or stale snapshots keep recovery pending. Buffered newer
ticks take precedence over older snapshot rows; same-time conflicting values
keep recovery pending. Quotes are hidden after entitlement/session/source fatal
errors or close. The supervisor still owns cleanup of such terminal sessions.

`RECONCILED` means all mapped instruments passed this recovery check; it does
not certify exchange realtime, supplier permission, trading-session status or
end-to-end delivery. `RECOVERY_REQUIRED`, `RECONNECTING`, `STALE_DATA` and the
underlying sanitized transport status must remain visible to future consumers.
An idle stale feed is marked stale; this coordinator does not repeatedly poll
snapshots just to make its timestamps appear current.

This is a synchronous worker component. View reads serialize with a running
step and can wait for the native snapshot request. Do not call it directly on
an HTTP/UI request thread: the future service should publish immutable views
from the worker. SDK `HTTPTimeout=15` is configured, but real native timeout and
process supervision require integration verification. No background task,
HTTP endpoint or deployment is installed by importing these modules.

### Backend feed envelope v1

`MarketQuoteGateway` now requires an explicit health envelope. Bare `quotes`
arrays from the earlier candidate contract are intentionally rejected; no live
provider was configured when this contract was changed. An upstream service
must supply all four fields:

| Field | Meaning |
| --- | --- |
| `schemaVersion` | Integer `1` |
| `state` | Coordinator state from the allowlist below |
| `publishedAt` | UTC/offset ISO instant when the worker published this immutable view |
| `quotes` | Array of normalized quotes with unchanged `sourceAt` |

Allowed states: NEW, STARTING, WAITING_DATA, PENDING_AUTHORIZATION,
ENTITLEMENT_ERROR, SESSION_LOST, SOURCE_ERROR, CLOSED, RECONNECTING,
RECOVERY_REQUIRED, RECONCILED, STALE_DATA. Raw transport RECEIVING is not a
reconciliation result and is rejected. Additional internal transport details
are ignored by the gateway; raw vendor errors are never exposed on its board.

The gateway accepts quote updates only for RECONCILED or STALE_DATA. Recovery
and reconnect responses preserve previously attributed cache prices but expose
SOURCE_ERROR to existing clients; they cannot introduce new prices. Terminal
session/permission/source states clear the cache. Permission failures map to
PENDING_AUTHORIZATION. Precise status is separately available in `feedState`.

`publishedAt` must be no older than 30 seconds and no more than 5 seconds ahead
of the backend clock. The gateway also checks this age at each board read, so
a stopped poller cannot indefinitely advertise a healthy connection. Older
health envelopes, or different states at an identical publication timestamp,
cannot reverse a more recent state. All timestamps require synchronized clocks;
these thresholds measure worker health, not exchange data latency or a vendor
service-level guarantee. Quote staleness still uses sourceAt and catalogue
cadence independently, including daily/weekly/monthly instruments.

Board fields `feedState`, `feedPublishedAt`, `feedAgeSeconds` are additive.
`lastSuccessAt` describes successful ingestion of an accepted quote envelope,
not a new exchange tick. Unhealthy HTTP responses and invalid envelopes retain
cache with an error; repeated reads never update sourceAt. Feed quotes and
health are published atomically inside the backend.

`choice_feed.py` now augments `ChoiceRecovery.view()` with schemaVersion and
publishedAt and publishes immutable views. **Do not stamp a new publishedAt on
each HTTP GET**: a stuck worker must age out. Process supervision, private
credential configuration, frontend detail/status labels and real supplier
activation remain subsequent integration work.

### Opt-in local HTTP publication

`QuotePublisher(recovery, authorized=False)` owns immutable JSON bytes. Its
default never invokes recovery/SDK operations and publishes PENDING_AUTHORIZATION.
After checking real permission, an owning worker calls `step()` periodically;
HTTP readers call `read()` using a separate short publication lock. A blocked
SDK step leaves the prior publication timestamp intact and cannot block reads.
Worker failures publish SOURCE_ERROR without exception text or cached prices.
Only known quote fields are copied; internal transport details are omitted.
NaN, unknown states and payloads over 1 MiB fail closed.

`LocalQuoteServer(publisher, token, port=0)` binds **only 127.0.0.1** and serves
GET /quotes. Supply a private randomly generated 32–512 character token from
protected configuration; accepted characters are letters, digits and `_.~-`.
Send it as Authorization: Bearer, using the backend bearer-token configuration.
No default token, token file, SDK secret or credential lookup is bundled.

Use the server as a context manager or explicitly call start()/close(). The
local port can be read through `.port` (port 0 chooses an ephemeral port for
tests). Missing/wrong/duplicate authorization headers are denied, query tokens
are not accepted, responses are no-store, and request logs are suppressed.
The service permits at most eight request threads with two-second socket
timeouts. This is a private loopback bridge, not a public web server. Do not
proxy it to an external interface or let the browser hold its token.

`publisher.close()` publishes CLOSED immediately and prevents any in-flight
step from resurrecting old data. It does **not** stop the SDK. The supervisor
must separately close the subscription in finally, stop the worker, and close
the HTTP server. Importing the module starts nothing; constructing a server
binds a local socket, but does not start its serving thread until start().

No LaunchAgent/systemd unit or actual supplier configuration is installed here.
Call step only from an owning worker, not from an HTTP request. Readiness of
this local bridge does not establish exchange realtime or 24-hour operation.

### Periodic worker ownership

`choice_worker.py` provides `ChoiceWorker(subscription, publisher,
authorized=False, interval=1)`. All three authorization flags (subscription,
publisher, worker) must reflect the same checked supplier entitlement. Default
worker authorization closes publication as PENDING_AUTHORIZATION and never
starts the SDK or a background worker. Flags cannot grant supplier permission.

With authorization enabled, start() creates one daemon worker thread which owns
SDK start, periodic publisher.step and SDK close in finally. Every step completes
before waiting the configured interval (0.05–10 seconds, default one second).
There is no accumulated job queue, overlapping step, automatic relogin or retry
of a terminated session. Transport reconnect remains SDK-owned; recovery's
snapshot backoff still applies. RUNNING means the worker loop is active, not
that exchange realtime data is verified.

stop(timeout=5) signals shutdown and closes publication immediately. It waits
at most the configured timeout (0–30 seconds) and returns false with STOP_TIMEOUT
if a native SDK operation or cleanup is still blocked. It never calls SDK close
concurrently from another thread or force-kills shared services. When that native
call returns, the worker performs its own cleanup; late results cannot resurrect
the closed publication. **Do not start another SDK session in the same process
after STOP_TIMEOUT**. A permanently hung SDK needs an external process supervisor;
this module does not implement one.

wait(timeout=5) returns whether the worker finished. A true return from wait/stop
means termination, not clean termination: inspect status().state. CLEANUP_ERROR
means SDK cancellation/logout failed, publication is SOURCE_ERROR, and the process
must not be reused for another SDK login. Session/entitlement errors retain their
terminal reasons in both publication and worker status. Error text is not exposed.

Instances are single-use, including denied starts and stop-before-start. The
caller owns LocalQuoteServer lifetime and must close it separately. Do not call
subscription.start/close or publisher.step independently once a worker owns them.
This is an opt-in library component, not an installed system service or a claim
of unattended 24-hour operation. Process-level configuration and real SDK timeout
validation remain necessary before activation.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -p 'test_choice_*.py' -v
```

Tests use synthetic SDK responses, no external network, no vendor login, no real prices.
Python HTTP tests use loopback only; the separate Java `ChoiceFeedProcessTest`
starts a Python child with an explicitly synthetic SDK and pulls the actual
loopback endpoint into MarketQuoteGateway. It verifies periodic publication,
changed prices/source time, bad-token denial, permission loss, and frozen worker
heartbeat expiry/recovery. Heartbeat expiry uses an offset Java clock; it is
not a 30-second soak or an exchange-latency measurement.

Run the cross-process gate with Python 3 available and an explicitly isolated
`qiqihar_enterprise_test` database selected by QIQIHAR_TEST_DB_URL:

```sh
./scripts/mvn-jdk21.sh -q -Dtest=MarketQuoteGatewayTest,ChoiceFeedProcessTest test
```

The repository test listener resets that dedicated test database. Never point
it at a production database. The fixture is under scripts/tests, requires the
`--synthetic-test-only` flag, receives a random test-only HTTP token via its
environment, and never imports a real vendor SDK. Commands use its private
stdin pipe, not an HTTP control endpoint. Handshakes and cleanup are bounded;
JUnit forcibly terminates only its own fixture process if graceful cleanup
fails, and treats such forced cleanup as test failure. This does not test the
Spring scheduler, public REST routing, browser display or a real provider.

Signatures and event codes were checked against official Python SDK 2.7.7.0,
downloaded from:
https://cftdlcdn.eastmoney.com/Choice/EMQuantAPI/EMQuantAPI_Python.zip

Archive SHA-256:
`24abc1965fbdfbed3adb545cf5b7703e44223d8233d43c43c1bc5a50d4ab1857`.
Sources: `python3/EmQuantAPI.py` (`start`, `csq`, `csqcancel`, `stop`) and
`python3/demo.py` (`mainCallback`, realtime subscription example). No vendor
source or binary is included in this repository. Official manual:
https://quantapi.eastmoney.com/Upload/EMQuantAPI_Python.html

## Read-only configuration preflight

`choice_preflight.py` checks a proposed local configuration without importing the
vendor SDK, logging in, opening a listening socket, generating credentials or
starting a worker. The foreground launcher below shares this validation and
uses the same in-memory values for construction. Exit 0 means local configuration validation passed;
exit 2 means configuration is missing or invalid. Both return `readyForLive:false`
and `supplierPermission:NOT_CHECKED`. A successful state is deliberately named
`CONFIG_VALID_VENDOR_CHECK_REQUIRED`.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/market_data/choice_preflight.py --config /absolute/path/choice-config.json
```

Configuration schema (all keys required; unknown keys rejected):

```json
{
  "schemaVersion": 1,
  "distributionAuthorized": false,
  "host": "127.0.0.1",
  "port": 19091,
  "intervalSeconds": 1,
  "catalogueFile": "catalogue.json",
  "bindingsFile": "bindings.json",
  "bearerTokenFile": "bearer.txt"
}
```

Paths are relative to the config file, or absolute. JSON files must be regular
files, at most 1 MiB, with no duplicate keys or non-finite constants. Final path
components cannot be symlinks. Parent directories must be trusted; this tool is
not a filesystem sandbox. No files are written. Diagnostics contain fixed reason
codes, not supplied values, paths, tokens or exception text.

`distributionAuthorized:true` is an operator declaration for permitted internal
redistribution, not evidence of a supplier entitlement. Leave it false until
actual permission is obtained. Catalogue JSON is an ID-to-unit object exported
from the target backend; bindings are the verified records consumed by
`QuoteNormalizer` (`code`, `id`, `unit`, `verified:true`). The checker validates
consistency between these files, not their provenance, provider codes, contract
roll rules or agreement scope. Never use synthetic test codes as production
mappings. Refresh the catalogue when the backend catalogue changes.

The bearer file contains the **local HTTP feed token**, not the Choice account
password. It must be owned by the current OS user, exactly mode 0600, with
32–512 ASCII characters from `[A-Za-z0-9_.~-]` and optionally one final newline.
Use an independently generated random token, kept outside Git; validation checks
format, not entropy. This stage creates no real token. Host must be 127.0.0.1,
port an integer 1–65535, interval 0.05–10 seconds. Port availability, native SDK
compatibility, source timestamp semantics and actual supplier permissions are
not tested. A standalone preflight is a point-in-time check. The foreground
launcher validates again at startup and uses that same checked configuration.

## Foreground process ownership

`choice_process.run_service(factory, lock_path, authorized=False)` owns the worker
and local HTTP server lifecycle in a dedicated POSIX process (macOS/Linux). The
factory returns `(ChoiceWorker, LocalQuoteServer)` and must only construct them;
SDK login remains inside `ChoiceWorker.start`. The lock is acquired before the
factory is called, so a competing process cannot bind the server or log in.
Authorization defaults to denied and creates no lock or components.

The design uses a nonblocking OS `flock`, rather than a PID-file existence check
or deleting a stale file. The lock file remains on disk after normal exit; only
the held descriptor establishes ownership. Never delete or replace that file
while a process may be using it. All launchers for the same feed/account must use
the same lock path in a persistent, pre-existing private directory owned by the
current user with mode 0700. The lock file must be a regular, single-link file
owned by that user with mode 0600. Final directory/file symlinks are rejected;
ancestor directories must be trusted. This is an advisory, local-machine lock,
not protection against non-cooperating launchers or another host's login.

Call from the main thread. SIGINT/SIGTERM request shutdown; previous handlers are
restored after cleanup. Normal shutdown stops the worker (default wait 5 seconds,
maximum 30), closes local HTTP, then releases the instance lock. Existing server
shutdown additionally has its own bounded wait. Supplier terminal errors exit
without retry/relogin. `STOP_TIMEOUT`, failed construction/start, and uncertain
cleanup retain the lock descriptor in the process until it exits. Even if a
blocked SDK later returns, that process must not restart the service. The owner
must exit its dedicated process after **every** return. A supervisor may restart
only after that process has actually terminated; do not infer termination from
an HTTP endpoint closing. This function never force-kills a process or service.

Return values contain sanitized `exitCode` and `state`:

| Exit | Meaning |
| --- | --- |
| 0 | Stop requested and cleanup completed |
| 2 | Authorization opt-in missing |
| 3 | Worker terminated on its own; inspect terminal state |
| 4 | Another instance owns the lock or lock path is unsafe |
| 5 | Start/cleanup uncertain or SDK stop timed out; process exit required |

A factory that raises partway through construction must close any resources it
has not returned; the wrapper cannot recover references it never received. It
quarantines the instance lock and returns an exit-required result. This lifecycle
module does not load SDKs or install services. The foreground launcher below
assembles checked configuration under this lock. Actual vendor entitlement
and remote backend token configuration still require separate validation.

The process tests use real file locks, worker threads, loopback HTTP servers and
separate child processes with synthetic sessions. They verify competing start,
SIGTERM/SIGINT, restart after normal exit, terminal permission failure, blocked
SDK shutdown and cleanup-error quarantine. No real vendor SDK, credentials or
prices are involved.


## Configuration-driven foreground launcher

`choice_launcher.py` connects checked configuration to the real subscription,
normalizer, recovery, immutable publisher, loopback HTTP and worker components.
Default invocation performs **check-only** and never imports SDK code, creates
an instance lock, binds HTTP or logs in:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/market_data/choice_launcher.py --config /absolute/path/choice-config.json
```

After the supplier has granted the required permissions, mappings/source time
semantics are verified, and native SDK compatibility is checked, an operator can
explicitly request foreground execution:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 scripts/market_data/choice_launcher.py --config /absolute/path/choice-config.json --run --lock-file /absolute/private-runtime/choice.lock --sdk-file /absolute/official-sdk/python3/EmQuantAPI.py
```

These are operator command templates, not a statement that this installation
has permission or is running. `--run` additionally requires the config's explicit
internal-distribution authorization declaration, a lock path, and SDK path.
The flag cannot grant a vendor entitlement. No real credentials or tokens are
included; local token files remain private and Choice login remains SDK-owned.

Every config/catalogue/bindings/token file is read once per invocation. Only a
valid complete set produces private in-memory runtime values. No configuration
file is read again during assembly or recovery; changes take effect on a later
process start. This is not a multi-file filesystem transaction: publish a
consistent configuration set before starting. Public preflight JSON stays
sanitized, and the runtime object's default repr does not print the token.

The SDK loader executes the explicitly selected regular UTF-8 Python file and
checks the required `c` methods. It never searches sys.path for another SDK or
writes a compiled bytecode cache. It rejects final symlinks and files over 8 MiB.
**That file is executable trusted code**: use only the reviewed official download
with its original neighboring native libraries in a trusted directory. These
checks do not verify publisher authenticity, native architecture, ABI or arbitrary
import-time side effects. No SDK is bundled or automatically downloaded. Imports
occur only after instance-lock acquisition; SDK login begins only in the worker.
The wrapper sanitizes its own exception results; vendor/native import-time output
is controlled by that SDK, so operator logs must remain private.

The launcher prints a final JSON result and exits with the lifecycle exit code.
It stays in the foreground until a stop signal or worker terminal condition.
It does not install LaunchAgent/systemd, persist credentials, change the backend
feed URL, or claim 24-hour availability. A hung native SDK needs a dedicated
external supervisor after native-runtime verification. TIME values still require
full ISO timestamps with timezone; time-only strings are rejected and are never
silently assigned the receipt date. Main-contract and previous-close semantics
are not guessed. The initial subscription requests only TIME,NOW.

Tests load synthetic SDK objects/files only. The full CLI subprocess test
validates explicit --run, authenticated loopback snapshot delivery, SIGTERM exit,
and lock release. Another integration test modifies config inputs after their
validation and confirms the assembled service still uses the checked values.

## macOS SDK environment prerequisite (verified 2026-09-26)

Official SDK 2.7.7.0 discovers its macOS native library through an
`EmQuantAPI.pth` file found in the selected Python environment's `site-packages`.
Passing `--sdk-file` alone does **not** establish that native-library path. The
`.pth` must contain one absolute path to the original `python3` SDK directory,
whose `libs/mac/libEMQuantAPIx64.dylib` and adjacent resources remain intact.
Use a dedicated virtual environment; do not change a shared/system Python's
site-packages. A `.pth` is part of trusted Python startup configuration; only
write the intended absolute directory, never downloaded executable statements.
Confirm SDK discovery points to the same reviewed bundle as `--sdk-file` before
starting a session; an unrelated existing `.pth` can select a different library.
The launcher now cross-checks that native-library selection before returning
the SDK object to the worker (see the path guard below).

A local isolated probe used the previously downloaded archive with SHA-256
`24abc1965fbdfbed3adb545cf5b7703e44223d8233d43c43c1bc5a50d4ab1857` (download
fingerprint, not a vendor signature). On macOS 26.5.2, arm64 Python 3.14.6:

- The universal native library contains arm64 and x86_64; minimum macOS is 13.0.
- The current launcher's Python loader imported the official wrapper.
- The wrapper resolved the intended library through the isolated `.pth`.
- A statically reviewed initialization method loaded that library and bound
  45 native function signatures; no login, query, subscription, snapshot,
  activation or other native business function was invoked.
- The probe ran in a child process with networking denied and writes restricted
  to its isolated scratch directory, and completed with empty stderr.
- Python emitted one DeprecationWarning for the SDK's packed `stOrderInfo`
  structure, concerning future Python 3.19 layout behavior. The vendor source
  was not patched. This is not proof of all callback or native ABI behavior.

This establishes a usable no-login loading setup on that local environment,
not supplier activation, support certification, real-price delivery, portability
to another machine, or service deployment. Keep the original package and
fingerprints for revalidation when Python, the SDK or the OS changes. Actual
entitlement and full timestamp/callback behavior remain activation gates.


## SDK native-path guard

The CLI SDK loader currently supports only the verified macOS path. Other OSes
fail with `SDK_PLATFORM_NOT_VERIFIED` **before SDK import**. Linux/Windows need a
separate verified startup path; the official Linux ARM wrapper can re-exec the
process on import, so it is not safe to assume the same lifecycle behavior.
Injected synthetic loaders used in isolated tests are not the CLI vendor loader.

After importing the trusted wrapper, the loader calls only its
`UtilAccess.GetLibraryPath()` discovery helper. It requires an absolute path
matching `libs/mac/libEMQuantAPIx64.dylib` inside the selected wrapper's directory.
The expected library must be a regular file with no symlink redirection in its
relative library path. No native initialization or supplier login is called by
this check. Paths are canonicalized to allow normal aliases to the same SDK root.

| Reason | Meaning |
| --- | --- |
| SDK_NATIVE_PATH_UNAVAILABLE | Discovery helper missing/failed, empty path or relative path; check the selected virtualenv's .pth configuration |
| SDK_NATIVE_PATH_MISMATCH | SDK discovery selects a library outside the specified bundle |
| SDK_NATIVE_LIBRARY_INVALID | Expected library is missing, not regular, or redirected through symlinks |
| SDK_PLATFORM_NOT_VERIFIED | This OS startup path has not been validated |
| SDK_LOAD_FAILED | Other wrapper import or required-method validation failure |

These failures return exit code 5, preserve the lifecycle's exit-required lock
quarantine, and print only fixed reason codes. They do not print paths, secrets
or original exception messages. Correct the environment and launch a new
process. No automatic retry, fallback to another installation or forced login.

This is a startup path-consistency check, not a signature/hash/ABI check and not
a security sandbox for arbitrary Python SDK code. Keep the trusted .pth and SDK
files stable during startup and execution: the vendor wrapper resolves the path
again when it initializes. The guard does not freeze those files or monkey-patch
the vendor resolver. Validate a new SDK build separately before replacing it.

Verified against the real official wrapper in a network-denied child process:
correct isolated .pth accepted with native initialization still false, no .pth
rejected, and a .pth pointing to another bundle rejected. Unit/CLI tests use
inert SDK fixtures and dummy library files which are never dynamically loaded.

## Disabled macOS service configuration

`choice_launchagent.py` prepares a **disabled** LaunchAgent plist only. It never
runs launchctl, installs into Library/LaunchAgents, enables a job, starts Python,
reads a token/config file, or imports the SDK. For example, after creating a
private runtime directory owned by the service user with mode 0700:

```sh
python3 scripts/market_data/choice_launchagent.py --python /absolute/venv/bin/python --launcher /absolute/release/scripts/market_data/choice_launcher.py --config /absolute/private-runtime/choice-config.json --sdk-file /absolute/official-sdk/python3/EmQuantAPI.py --runtime-dir /absolute/private-runtime --output /absolute/review/choice.plist
```

The output is mode 0600, created exclusively (existing files are never replaced).
All arguments must be absolute paths; the virtualenv interpreter path is kept
literally, including spaces, and is not resolved through its symlink to a system
interpreter. ProgramArguments is an array, not a shell command. The plist contains
file paths only, not token values or account credentials. Target executable,
config and SDK paths are not activated or validated by this generator; validate
them through the deployment gate before enabling the job.

The fixed service label is `com.cofco.qiqihar.market-choice`, independent from
the formal business stack. `Disabled=true`, `RunAtLoad=false`, `KeepAlive=false`
and no timer/watch trigger prevent automatic start or retry from this template.
Launchd's persisted enable/disable overrides can supersede plist defaults: inspect
the actual service domain before any later registration, and never assume the
file alone proves a registered job is disabled. No registration occurs here.

Stdout/stderr go to fixed files under the private runtime directory; Umask is
63 decimal (octal 077), so newly created logs are private. Existing logs must be
ordinary, single-link files owned by the current user with mode 0600; symlinks
are rejected. Parent directories must be trusted. This is a generation-time
check, not a guarantee against later filesystem changes. Logs may contain vendor
output: keep them private and arrange bounded rotation before long-term use.

`ExitTimeOut=15` gives launchd a finite SIGTERM-to-SIGKILL window for this dedicated
job when it is stopped; the worker normally waits five seconds for cleanup.
This is a configured policy, not proof that launchd stop behavior was exercised.
`ProcessType=Background` applies normal background resource policy. These keys
were checked against the installed macOS launchd.plist(5) manual.

There is intentionally no exit-triggered restart: permission/configuration
failures and blocked native calls must not lead to automatic login loops. An
activated healthy foreground worker can continue collecting, but this template
does not by itself provide reboot recovery, crash recovery, system wakefulness,
or 24-hour availability. User LaunchAgents also depend on the user's login
session. Those operational behaviors need a later, authorized service acceptance
stage after supplier permission and real-data validation. Do not enable this
artifact simply because plist syntax and synthetic tests pass.
