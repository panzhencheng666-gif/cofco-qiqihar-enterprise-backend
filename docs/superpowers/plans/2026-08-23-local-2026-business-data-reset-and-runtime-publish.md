# Local 2026 Business Data Reset and Runtime Publish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Safely remove local 2026 corn, rice, and soybean production/market operational data and publish the verified three-repository build to the managed 63182/8090/63200 runtime.

**Architecture:** A repository-owned PostgreSQL maintenance script performs preview and apply with a content digest, strict local database guard, legal-hold check, one transaction, and postconditions. Runtime publication reuses existing managed-copy scripts; no database is copied.

**Tech Stack:** PostgreSQL 17/psql, Bash, SHA-256, Java 21, Node 24, existing local launchd/runtime scripts, Playwright.

## Global Constraints

- Only `qiqihar_enterprise_dev` on numeric loopback is eligible for apply.
- Year must equal 2026 and products must equal `CORN,RICE,SOYBEAN`.
- Preview is mandatory; apply requires the exact preview digest.
- Preserve 2332 village references, other years, users, roles, templates, master data, immutable audit, legal holds, and unrelated sample points.
- Do not copy the database and do not touch cloud or production.

---

### Task 1: Build the guarded reset script

**Files:**
- Create: `scripts/local-2026-business-data-reset.sql`
- Create: `scripts/local-2026-business-data-reset.sh`
- Create: `scripts/verify-local-2026-business-data-reset.sh`

**Interfaces:**
- Produces: `--preview` JSON/TSV manifest and digest; `--apply --digest <sha256>` transactional reset.

- [ ] **Step 1: Write the failing verifier**

Create mixed 2024/2025/2026 fixtures in `qiqihar_enterprise_test`; assert remote/database-name rejection, legal-hold rejection, digest-drift rejection, rollback on forced postcondition failure, successful target deletion, and unchanged non-target counts.

- [ ] **Step 2: Run verifier**

Expected: FAIL because the reset scripts do not exist.

- [ ] **Step 3: Implement preview SQL**

Materialize target record, draft, work-item, report, supply, and orphan-candidate IDs in temporary tables. Emit ordered per-table counts plus invariants, then hash the canonical output. Never emit business values.

- [ ] **Step 4: Implement apply SQL**

Acquire an advisory transaction lock, repeat target selection, compare digest, reject active legal holds, delete child rows before parents, remove only unreferenced 2026-only sample points, write one scope/count audit event, and assert zero targets plus unchanged non-target sentinels before commit.

- [ ] **Step 5: Implement shell guard**

Reject non-loopback host and any database other than `qiqihar_enterprise_dev` for apply. Default to preview. Use `ON_ERROR_STOP=1`; never accept a password argument or print credentials.

- [ ] **Step 6: Run verifier on `qiqihar_enterprise_test`**

Expected: all safety scenarios PASS.

- [ ] **Step 7: Commit reset tooling**

`git add scripts/local-2026-business-data-reset.sql scripts/local-2026-business-data-reset.sh scripts/verify-local-2026-business-data-reset.sh && git commit -m "chore(local): add guarded 2026 business data reset"`

### Task 2: Preview and apply to the local development database

- [ ] **Step 1: Stop write traffic or place the local service in a controlled maintenance window**
- [ ] **Step 2: Run `--preview` and record counts/digest**
- [ ] **Step 3: Recheck active legal holds and current database identity**
- [ ] **Step 4: Run `--apply --digest <preview-digest>`**
- [ ] **Step 5: Requery 2026 production, market, visible drafts, linked work items, reports, supply results, and orphan constraints**
- [ ] **Step 6: Requery 2332 village/coordinate counts and non-target year sentinels**

Expected: all 2026 target operational counts are zero; 2332 village references remain; non-target sentinels are unchanged.

### Task 3: Build and publish managed local runtime copies

- [ ] **Step 1: Build Backend with JDK 21 and run required gates**
- [ ] **Step 2: Build Frontend and Web with Node 24 and run required gates**
- [ ] **Step 3: Use existing managed publish scripts to sync source-owned runtime candidates**
- [ ] **Step 4: Verify source/runtime SHA-256 manifests**
- [ ] **Step 5: Restart managed Backend 8090, Web 63182, and internal Frontend 63200**
- [ ] **Step 6: Verify health and same-origin proxy routes**

### Task 4: Browser business acceptance

- [ ] **Step 1: On 63182 verify annual network governance permissions and lifecycle**
- [ ] **Step 2: Verify actual/design/comparison map modes and unchanged real icons**
- [ ] **Step 3: Verify production, market, and supply coverage strips**
- [ ] **Step 4: Verify cleared 2026 pages show explicit no-approved-data states rather than zero-filled metrics**
- [ ] **Step 5: Verify browser console/network has no related errors**
- [ ] **Step 6: Record exact three-repository SHAs, runtime hashes, reset digest, counts, and remaining limitations**
