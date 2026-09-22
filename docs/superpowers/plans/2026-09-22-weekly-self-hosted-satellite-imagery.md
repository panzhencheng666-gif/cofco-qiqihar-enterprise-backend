# Weekly Self-Hosted Satellite Imagery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically publish the newest usable 10 metre Sentinel-2 view every Monday and serve it from local versioned storage without per-view upstream requests.

**Architecture:** A standalone Python/GDAL worker discovers Sentinel-2 L2A COG assets, produces an atomic weekly tile release, and retains four successful releases. The Java gateway serves local release metadata and tiles while preserving the existing remote fallback contract; the map client reads metadata once and changes its immutable tile URL only when the release version changes.

**Tech Stack:** Python 3 standard library, GDAL CLI, systemd, Java 21/Spring Boot 4.1, React 19, TypeScript 5.9, MapLibre GL 6.

## Global Constraints

- Sentinel-2 L2A is the default source; Chinese resource imagery remains disabled until its automated access and display licence are verified.
- The image is described as the latest available observation, never as live video or guaranteed same-day imagery.
- True-colour source detail is 10 metres and does not claim additional detail above approximately zoom 14.
- Every release records acquisition range, synchronization time, cloud cover, source product IDs, checksums and status.
- A failed run never changes the current release; retain four successful releases.
- Browser traffic never contacts the upstream imagery provider.
- Secrets remain in a protected production environment file and never enter Git, logs or API responses.

---

### Task 1: Deterministic weekly imagery worker

**Files:**
- Create: `scripts/weekly_imagery_sync.py`
- Create: `scripts/tests/test_weekly_imagery_sync.py`
- Create: `ops/imagery/qiqihar-aoi.geojson`

**Interfaces:**
- Consumes: STAC search endpoint and COG asset URLs configured by environment.
- Produces: `releases/<ISO-WEEK>/metadata.json`, `tiles/{z}/{x}/{y}.webp`, `manifest.sha256`, and an atomic `current` symlink.

- [ ] **Step 1: Write focused unit tests for weekly period selection, candidate ranking, retry idempotency, atomic publication and four-release retention.**

```python
def test_candidate_rank_prefers_newest_clear_authorized_product():
    selected = select_candidates([
        Candidate("old-clear", observed_at("2026-09-18"), 3.0, True),
        Candidate("new-cloudy", observed_at("2026-09-20"), 85.0, True),
        Candidate("new-clear", observed_at("2026-09-20"), 12.0, True),
    ])
    assert selected[0].product_id == "new-clear"

def test_publish_keeps_current_when_validation_fails(tmp_path):
    current = seed_release(tmp_path, "2026-W37")
    with pytest.raises(ReleaseValidationError):
        publish_release(tmp_path, invalid_staging_release(tmp_path, "2026-W38"))
    assert current.resolve().name == "2026-W37"
```

- [ ] **Step 2: Run the worker test file and verify the new module is absent.**

Run: `python3 -m unittest scripts/tests/test_weekly_imagery_sync.py -v`

Expected: FAIL because `scripts.weekly_imagery_sync` does not exist.

- [ ] **Step 3: Implement discovery, safe download, GDAL processing, manifest validation, atomic switch and retention.**

The worker exposes pure functions `complete_week(now: datetime) -> WeekWindow`, `search_candidates(config, window) -> list[Candidate]`, `rank_candidates(candidates) -> list[Candidate]`, `validate_release(path) -> ReleaseMetadata`, `publish_release(root, staging) -> Path`, and `retain_releases(root, keep=4) -> None`. The CLI accepts `--root`, `--now`, `--dry-run` and `--force`; all network and GDAL commands have timeouts and bounded output.

- [ ] **Step 4: Run the worker unit tests and a no-network dry run.**

Run: `python3 -m unittest scripts/tests/test_weekly_imagery_sync.py -v`

Expected: PASS.

Run: `python3 scripts/weekly_imagery_sync.py --root "$(mktemp -d)" --now 2026-09-22T00:00:00Z --dry-run`

Expected: prints the target period and exits without a network request or release mutation.

- [ ] **Step 5: Commit the worker.**

```bash
git add scripts/weekly_imagery_sync.py scripts/tests/test_weekly_imagery_sync.py ops/imagery/qiqihar-aoi.geojson
git commit -m "feat(imagery): build atomic weekly Sentinel-2 releases"
```

### Task 2: Production scheduler and guarded installer

**Files:**
- Create: `ops/systemd/cofco-weekly-imagery.service`
- Create: `ops/systemd/cofco-weekly-imagery.timer`
- Create: `scripts/install-weekly-imagery-worker.sh`
- Create: `scripts/tests/weekly-imagery-worker-install.test.sh`
- Modify: `LOCAL_RUNBOOK.md`

**Interfaces:**
- Consumes: Task 1 CLI and `/etc/cofco/weekly-imagery.env` with `COPERNICUS_*` settings.
- Produces: a hardened oneshot service and Monday timer with persistent catch-up and bounded retries.

- [ ] **Step 1: Write a shell contract test that requires the timer schedule, hardening, 0600 credential file, lock, disk guard and atomic release root.**

```bash
assert_contains ops/systemd/cofco-weekly-imagery.timer 'OnCalendar=Mon *-*-* 03:10:00 Asia/Shanghai'
assert_contains ops/systemd/cofco-weekly-imagery.timer 'Persistent=true'
assert_contains ops/systemd/cofco-weekly-imagery.service 'ProtectSystem=strict'
assert_contains scripts/install-weekly-imagery-worker.sh 'install -m 600'
assert_contains scripts/install-weekly-imagery-worker.sh 'systemctl enable --now cofco-weekly-imagery.timer'
```

- [ ] **Step 2: Run the contract test and verify it fails for missing units.**

Run: `bash scripts/tests/weekly-imagery-worker-install.test.sh`

Expected: FAIL naming the first missing unit.

- [ ] **Step 3: Add the units, guarded installer and exact operator runbook.**

The service runs as a dedicated non-login user, writes only to `/var/lib/cofco/imagery`, uses `flock`, enforces a six-hour timeout, and invokes the worker. The timer triggers Monday 03:10 Asia/Shanghai; service-level retry scheduling uses explicit retry markers without allowing two concurrent runs.

- [ ] **Step 4: Run shell syntax and contract checks.**

Run: `bash -n scripts/install-weekly-imagery-worker.sh && bash scripts/tests/weekly-imagery-worker-install.test.sh`

Expected: PASS.

- [ ] **Step 5: Commit scheduler support.**

```bash
git add ops/systemd/cofco-weekly-imagery.service ops/systemd/cofco-weekly-imagery.timer scripts/install-weekly-imagery-worker.sh scripts/tests/weekly-imagery-worker-install.test.sh LOCAL_RUNBOOK.md
git commit -m "feat(imagery): schedule unattended weekly synchronization"
```

### Task 3: Serve local versioned imagery and truthful metadata

**Files:**
- Create: `src/main/java/com/cofco/qiqihar/graintrade/overview/infrastructure/LocalImageryReleaseStore.java`
- Create: `src/test/java/com/cofco/qiqihar/graintrade/overview/infrastructure/LocalImageryReleaseStoreTest.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/overview/infrastructure/MapImageryTileGateway.java`
- Modify: `src/main/java/com/cofco/qiqihar/graintrade/overview/interfaceadapter/OverviewMapImageryController.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/cofco/qiqihar/graintrade/overview/infrastructure/MapImageryTileGatewayTest.java`
- Modify: `src/test/java/com/cofco/qiqihar/graintrade/overview/interfaceadapter/OverviewMapImageryControllerTest.java`

**Interfaces:**
- Consumes: Task 1 `current/metadata.json` and tile tree.
- Produces: metadata fields `version`, `acquisitionFrom`, `acquisitionTo`, `syncedAt`, `spatialResolutionMeters`, `cloudCoveragePercent`, `status`, `sourceProductIds`; immutable version-aware tiles.

- [ ] **Step 1: Write tests for local release precedence, traversal rejection, metadata parsing, stale status and remote fallback.**

```java
@Test
void servesTheCurrentLocalReleaseWithoutCallingTheRemoteProvider() throws Exception {
    var release = releases.current("2026-W38", "2026-09-20T03:00:00Z", 10, "CURRENT");
    var tile = gateway.tile("2026-W38", 14, 13871, 5612);
    assertThat(tile.bytes()).isEqualTo(release.tileBytes());
    assertThat(remoteRequests).hasValue(0);
    assertThat(gateway.metadata().spatialResolutionMeters()).isEqualTo(10);
}
```

- [ ] **Step 2: Run the focused Java tests and verify they fail for the missing store/version contract.**

Run: `./scripts/mvn-jdk21.sh -Dtest=LocalImageryReleaseStoreTest,MapImageryTileGatewayTest,OverviewMapImageryControllerTest test`

Expected: FAIL at compilation.

- [ ] **Step 3: Implement the release store, gateway precedence and versioned tile route.**

Add `GET /api/v1/overview/map-imagery/tiles/{version}/{zoom}/{x}/{y}` while retaining the existing unversioned route for compatibility. Resolve all files beneath the configured root using normalized paths and reject any mismatch. Cache metadata by file modification time rather than polling.

- [ ] **Step 4: Run focused Java tests and package.**

Run: `./scripts/mvn-jdk21.sh -Dtest=LocalImageryReleaseStoreTest,MapImageryTileGatewayTest,OverviewMapImageryControllerTest test`

Expected: PASS.

Run: `./scripts/mvn-jdk21.sh -DskipTests package`

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit local serving support.**

```bash
git add src/main/java src/main/resources/application.yml src/test/java
git commit -m "feat(imagery): serve versioned local satellite releases"
```

### Task 4: Map metadata badge and stable version switching

**Files (frontend repository):**
- Create: `src/modules/overview/infrastructure/http/HttpMapImageryRepository.ts`
- Create: `src/modules/overview/infrastructure/http/HttpMapImageryRepository.spec.ts`
- Create: `src/modules/overview/ui/components/mapImageryMetadata.ts`
- Create: `src/modules/overview/ui/components/mapImageryMetadata.spec.ts`
- Modify: `src/modules/overview/ui/components/fourRegionTerrainStyle.ts`
- Modify: `src/modules/overview/ui/components/OperationalSituationMap.tsx`
- Modify: `src/modules/overview/ui/components/OperationalSituationMap.spec.tsx`
- Modify: `src/modules/overview/ui/components/operational-situation.css`

**Interfaces:**
- Consumes: Task 3 metadata response and versioned tile route.
- Produces: a single metadata read per map mount and a stable MapLibre raster source URL containing the immutable version.

- [ ] **Step 1: Write repository/parser and UI tests.**

```ts
expect(imageryLabel(currentMetadata)).toBe(
  "Sentinel-2 · 10米 · 采集 2026-09-18至2026-09-20 · 周一同步",
);
expect(imageryWarning(staleMetadata)).toContain("沿用上一成功版本");
expect(satelliteTileUrl("2026-W38")).toBe(
  "/api/v1/overview/map-imagery/tiles/2026-W38/{z}/{x}/{y}",
);
```

- [ ] **Step 2: Run only the new and directly affected Vitest files and verify failure.**

Run: `npm test -- src/modules/overview/infrastructure/http/HttpMapImageryRepository.spec.ts src/modules/overview/ui/components/mapImageryMetadata.spec.ts src/modules/overview/ui/components/OperationalSituationMap.spec.tsx`

Expected: FAIL because the repository and label functions do not exist.

- [ ] **Step 3: Implement one-shot metadata loading, truthful badge and immutable source URL.**

Do not attach imagery metadata to business SSE invalidation. Replace the existing commercial-provider credit text with source/date/status from metadata. Recreate only the raster source when the version changes; do not recreate the map instance or right panel.

- [ ] **Step 4: Run focused tests, lint and build.**

Run: `npm test -- src/modules/overview/infrastructure/http/HttpMapImageryRepository.spec.ts src/modules/overview/ui/components/mapImageryMetadata.spec.ts src/modules/overview/ui/components/OperationalSituationMap.spec.tsx`

Expected: PASS.

Run: `npm run lint && npm run build`

Expected: PASS.

- [ ] **Step 5: Commit the frontend change in the frontend repository.**

```bash
git add src/modules/overview
git commit -m "feat(map): display truthful weekly imagery metadata"
```

### Task 5: Release, production synchronization and acceptance

**Files:**
- Modify: `.github/workflows/ci.yml`
- Create: `scripts/verify-weekly-imagery-release.sh`
- Create: `scripts/tests/verify-weekly-imagery-release.test.sh`

**Interfaces:**
- Consumes: Tasks 1-4 release artifacts and production environment.
- Produces: CI evidence, installed timer, first successful release, API readback and browser acceptance.

- [ ] **Step 1: Add deterministic worker and installer contract checks to backend CI.**

```yaml
- name: Verify weekly imagery worker
  run: |
    python3 -m unittest scripts/tests/test_weekly_imagery_sync.py -v
    bash scripts/tests/weekly-imagery-worker-install.test.sh
    bash scripts/tests/verify-weekly-imagery-release.test.sh
```

- [ ] **Step 2: Add a release verification script that checks timer state, current symlink, manifest, metadata age and a representative tile without printing secrets.**

Run: `bash scripts/tests/verify-weekly-imagery-release.test.sh`

Expected: PASS against its isolated fixture.

- [ ] **Step 3: Run only proportional local gates.**

Backend: focused imagery tests, worker unit tests, shell contract tests and package.

Frontend: focused imagery/map tests, lint and build.

Expected: all PASS; do not repeat unaffected database or browser suites.

- [ ] **Step 4: Push branches, open pull requests, wait for required CI and merge to each repository main.**

Expected: backend and frontend main contain the reviewed commits and required checks are green.

- [ ] **Step 5: Install production worker and deploy backend/frontend releases.**

Run the guarded installer with the protected Copernicus configuration, execute one manual `systemctl start cofco-weekly-imagery.service`, then deploy the application artifacts. Do not claim success if the first synchronization cannot authenticate or produce a valid release.

- [ ] **Step 6: Read back production state and perform one browser acceptance.**

Verify `/api/v1/overview/map-imagery/metadata`, one representative versioned tile, `systemctl is-enabled/is-active cofco-weekly-imagery.timer`, and the public situation map badge. Confirm repeated zooming and business realtime events do not request Copernicus URLs or recreate the map/right panel.

- [ ] **Step 7: Record the exact completion boundary.**

Report source/CI, production installation, first successful synchronization, API readback and browser evidence separately. If credentials are unavailable, report code and deployment preparation as complete but production weekly synchronization as externally blocked.
