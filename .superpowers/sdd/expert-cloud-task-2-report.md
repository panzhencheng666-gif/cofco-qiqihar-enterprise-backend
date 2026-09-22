# Expert cloud Task 2 implementation report

Status: implementation and initial review remediation complete; clean re-review pending.

## Implemented

- Added a private `active-expert-claim.json` lifecycle alongside the unchanged legacy claim file. The worker resumes saved expert work before claiming, persists monotonic progress/checkpoints, and replays upload/completion without repeating accepted phases.
- Added exact expert claim-to-local request mapping, synchronous and periodic lease heartbeats, cancellation acknowledgement, lease-loss discard, fixed redacted failure reporting, deterministic bundle upload, strict local candidate validation, and exact completion deletion semantics.
- Added loopback-only derivation of `/v1/expert-train` and `/v1/expert-train-cancel` from the existing trainer URL; no configuration keys or credentials were added.
- Added the authenticated strict-body cancellation endpoint outside `WORKLOAD_GATE`, plus a thread-safe owned-run registry that only terminates the subprocess group created for the matching run.
- Added cancellation propagation through `run_worker` and `train_expert`, staging/lock cleanup, and a fixed `EXPERT_TRAINING_CANCELLED` HTTP response without publishing a candidate.
- Added upgrade refusal for either legacy or expert active claim in the default and configured state roots.

## TDD evidence

Initial RED before production changes:

`python3 -m unittest test_remote_worker.py test_expert_training.py test_expert_training_http.py test_node_deployment.py`

Result: `Ran 77 tests in 44.429s`, `FAILED (failures=6, errors=14)`. Expected failures showed missing expert worker methods/hash/URLs, missing owned-run cancellation APIs and internal cancellation exception, missing cancel HTTP route, and missing active expert claim upgrade refusal.

Self-review RED for durable completion replay:

`python3 -m unittest test_remote_worker.RemoteWorkerContractTest.test_completion_response_loss_replays_only_exact_completion`

Result: `FAILED (failures=1)` because the retained claim restarted at `/progress` instead of replaying only exact completion. This drove persisted progress, result, and stored-artifact checkpoints.

Review-remediation RED evidence:

- Spawn/attach cancellation test: `test_cancel_in_spawn_attach_window_still_reaps_owned_child` failed because the spawned child remained live after `EXPERT_TRAINING_CANCELLED`.
- Restart test: `test_stale_lock_from_dead_parent_is_recovered` failed with `EXPERT_TRAINING_BUSY` for a dead owner's lock.
- Busy retry test: `test_local_workload_busy_is_retryable_and_does_not_mark_cloud_failed` failed because `503 WORKLOAD_BUSY` attempted `/failure`.
- Malformed success test: `test_malformed_local_success_is_acknowledged_as_redacted_permanent_failure` raised `JSONDecodeError` instead of sending the fixed redacted failure.
- Durable completion test with the uploaded local directory removed failed by calling `/failure` instead of replaying completion from the progress-95 checkpoint.
- Duplicate live cancellation test observed multiple `_stop` paths (including a second `killpg` failure) instead of a single atomic stop owner.

All six review regressions passed after the corresponding minimal fixes.

Final GREEN:

- `python3 -m unittest test_remote_worker.py test_expert_training.py test_expert_training_http.py test_node_deployment.py` -> `Ran 82 tests in 43.062s`, `OK`.
- `python3 -m unittest discover -s tools/risk-mlx-trainer -p 'test_*.py'` -> `Ran 130 tests in 42.896s`, `OK`.
- `bash scripts/tests/risk-training-node-local.test.sh` -> `RISK_TRAINING_NODE_LOCAL_CONTRACT_OK`.
- `python3 -m py_compile tools/risk-mlx-trainer/remote_worker.py tools/risk-mlx-trainer/expert_training_process.py tools/risk-mlx-trainer/expert_training.py tools/risk-mlx-trainer/server.py` -> exit 0.
- `git diff --check` -> exit 0.

The full discovery output includes the pre-existing HTTP request log lines from tests outside the changed expert HTTP fixture; it contains no test failures or warnings.

## Files changed

- `tools/risk-mlx-trainer/remote_worker.py`
- `tools/risk-mlx-trainer/expert_training_process.py`
- `tools/risk-mlx-trainer/expert_training.py`
- `tools/risk-mlx-trainer/server.py`
- `tools/risk-mlx-trainer/test_remote_worker.py`
- `tools/risk-mlx-trainer/test_expert_training.py`
- `tools/risk-mlx-trainer/test_expert_training_http.py`
- `tools/risk-mlx-trainer/test_node_deployment.py`
- `scripts/risk-training-node-local.sh`
- `scripts/tests/risk-training-node-local.test.sh`

Commits:

- `f4263f3 feat(trainer): run persistent expert cloud jobs`
- `61d1b37 fix(trainer): recover expert runs safely`
- `6cb9b66 fix(trainer): make cancellation and completion replay exact`

## Self-review

- Confirmed existing DOMAIN_LLM training/scoring and expert dataset/training response contracts remain intact; the only existing error-path change removes raw local response bodies from legacy worker exceptions.
- Confirmed cancellation never uses a caller-provided PID and only targets the registered `Popen` child process group for the exact safe run ID.
- Confirmed completion response loss retains a private checkpoint and retries only the exact stored reference/hash/metrics, avoiding cloud progress regression.
- Confirmed progress-95 completion replay does not require the already-uploaded local artifact to remain present.
- Confirmed exactly one stop owner terminates an active child even when duplicate cancellation and the training thread converge.
- Confirmed an exclusive advisory lock rejects a live owner but recovers a dead owner's lock and private staging directory after restart.
- Confirmed cloud/network/5xx paths retain state, while exact 409 lease loss discards only the expert state file and prevents upload/completion.
- Confirmed no new config key, credential, public listener, cloud resource, deployment, or real 27B/GPU run was introduced.

## Live-validation boundary

This report proves source behavior through substituted local process/HTTP/cloud boundaries and temporary deployment fixtures only. It does not prove installation on the managed Mac, live cloud authentication, real cloud lease/cancel calls, a real MLX 27B run, upload against deployed object storage, restart under launchd, or production deployment. Those remain deployment acceptance after code review.
