# Risk Intelligence Service Local Cutover Plan

**Goal:** Move the already implemented risk workbench, model operations, daily training, automatic promotion, and rollback runtime into the independent local service, then route only `/api/v1/risk/**` from the application-center frontend to it.

## Constraints

- Keep the existing enterprise backend on `127.0.0.1:8090` and all non-risk routes unchanged.
- The independent service continues to use `qiqihar_risk_runtime_login` and may write only schema `risk`.
- Reuse the tested risk algorithms and repositories; do not replace real operations with static responses.
- Preserve current API response shapes consumed by the risk frontend.
- Accept local user identity only from the loopback frontend proxy, validate its syntax, and persist the actor on feedback/training requests.
- Only the independent process may run the daily risk training worker after cutover.
- Model artifacts live outside PostgreSQL in the risk runtime directory. The local MLX trainer stays a separate loopback process.

## Tasks

1. Add failing standalone API and worker wiring tests.
2. Mechanically migrate the existing risk domain/application/repository code into the standalone artifact and replace dependencies on enterprise-wide access/audit components with risk-local request identity and error handling.
3. Configure the standalone worker, persistent model artifact path, MLX trainer endpoint, model reference, and secrets.
4. Add a dedicated Vite proxy for `/api/v1/risk/**` while leaving every other API on port 8090.
5. Redeploy the risk LaunchAgent, disable the legacy backend risk worker, and restart both managed services.
6. Verify live workbench/model APIs through the application-center origin, enqueue a real training execution, observe persisted processing outcome, and verify existing-system health.

## Completion Gate

- `/api/v1/risk/workbench/assessments` and `/api/v1/risk/models/overview` are served by port 63184 with the existing response contract.
- A manual training request persists the requesting actor and is processed by the independent worker.
- Daily schedule reconciliation and automatic lifecycle jobs are active only in the independent service.
- The frontend routes risk APIs to 63184 and all other APIs to 8090.
- Both repositories are clean with bounded commits and the existing local health suite remains green.
