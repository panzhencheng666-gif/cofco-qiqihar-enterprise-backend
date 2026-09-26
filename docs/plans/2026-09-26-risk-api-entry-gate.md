# Market backend risk API availability gate

Scope: root backend HTTP routes /api/v1/risk and descendants only. Preserve independent risk-intelligence-service and shared business permissions.

Design: a WebMvcConfigurer/HandlerInterceptor blocks risk requests with HTTP 503 and RISK_API_DISABLED before handler execution when qiqihar.risk.api.enabled is absent or false. Explicit true preserves existing security and services. The default remains false even with training enabled. This gate does not grant access or stop background training; keep the training flag false independently.

Implementation plan:
1. Exercise both existing risk controllers using MockMvc in a minimal MVC application context. Verify disabled GET/POST routes never invoke services; verify unrelated routes, context path handling, and explicit enablement.
2. Observe failing disabled-route tests before adding the interceptor.
3. Add early ordered interceptor, environment-bound default-false setting and operator description.
4. Run focused gate tests and existing risk service/worker tests; inspect diff, commit only this bounded change.

Done: tests and commit. Not done here: main integration, real deployment, rebuilt release evidence, specialized risk authorization when enabled.

Operator boundary: the managed launcher accepts QIQIHAR_RISK_API_ENABLED. Keep it false until specialized risk authorization and real-identity acceptance are complete. Setting true only removes the availability gate; it does not correct or strengthen existing shared business permissions. No formal runtime configuration is changed by this stage.
