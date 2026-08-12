-- Preserve the historical 2,587 local development grants while reducing the active
-- assignment to the 232 township responsibility anchors.  Runtime authorization
-- derives each township's villages, so leaf access remains complete without keeping
-- redundant prefecture/county/village grants active.  This is forward-only,
-- transactional and recoverable from the effective-dated rows below.

WITH expired AS (
    UPDATE platform.security_user_region_scope scope
       SET valid_until = CURRENT_TIMESTAMP,
           last_reviewed_at = CURRENT_TIMESTAMP,
           review_due_at = CURRENT_TIMESTAMP + INTERVAL '90 days'
      FROM platform.region region
     WHERE scope.subject_id = 'wang-yang'
       AND region.code = scope.region_code
       AND region.administrative_level <> 'TOWNSHIP'
       AND CURRENT_TIMESTAMP >= scope.valid_from
       AND (scope.valid_until IS NULL OR CURRENT_TIMESTAMP < scope.valid_until)
       AND EXISTS (
           SELECT 1
             FROM platform.security_user_region_scope township_scope
             JOIN platform.region township ON township.code = township_scope.region_code
            WHERE township_scope.subject_id = scope.subject_id
              AND township.administrative_level = 'TOWNSHIP'
              AND CURRENT_TIMESTAMP >= township_scope.valid_from
              AND (township_scope.valid_until IS NULL OR CURRENT_TIMESTAMP < township_scope.valid_until)
       )
    RETURNING scope.region_code
), summary AS (
    SELECT count(*)::integer AS expired_count FROM expired
)
INSERT INTO platform.business_audit_event(
    event_id, aggregate_type, aggregate_id, action_code, actor_subject_id,
    work_unit_code, occurred_at, detail)
SELECT CAST('7d3db587-2e84-4d50-a63b-b7bca72a6e34' AS uuid),
       'SECURITY_USER', security_user.subject_id,
       'SECURITY_USER_REGION_SCOPE_NORMALIZED', security_user.subject_id,
       security_user.work_unit_code, CURRENT_TIMESTAMP,
       jsonb_build_object(
           'expiredRedundantGrantCount', summary.expired_count,
           'activeTownshipAnchorCount', (
               SELECT count(*)
                 FROM platform.security_user_region_scope scope
                 JOIN platform.region region ON region.code = scope.region_code
                WHERE scope.subject_id = security_user.subject_id
                  AND region.administrative_level = 'TOWNSHIP'
                  AND CURRENT_TIMESTAMP >= scope.valid_from
                  AND (scope.valid_until IS NULL OR CURRENT_TIMESTAMP < scope.valid_until)),
           'derivation', 'TOWNSHIP_INCLUDES_DESCENDANT_VILLAGES',
           'recoverable', true)
  FROM platform.security_user security_user
 CROSS JOIN summary
 WHERE security_user.subject_id = 'wang-yang'
   AND summary.expired_count > 0;

COMMENT ON TABLE platform.security_user_region_scope IS
    'Effective-dated explicit responsibility anchors. Runtime authorization includes descendant regions; historical grants remain recoverable.';
