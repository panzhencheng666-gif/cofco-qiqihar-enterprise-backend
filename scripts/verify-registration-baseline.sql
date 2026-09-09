-- Read-only readiness check for the local registration preview, before serving it.
DO $$
BEGIN
  IF (SELECT count(*) FROM platform.work_unit WHERE active AND code IN
      ('QIQIHAR_BUSINESS','NEHE_DEPOT','KESHAN_DEPOT','KEDONG_DEPOT','LONGZHEN_DEPOT','CHENGJISIHAN_DEPOT')) <> 6
     OR NOT EXISTS(SELECT 1 FROM platform.work_unit_region_scope WHERE work_unit_code='QIQIHAR_BUSINESS')
     OR NOT EXISTS(SELECT 1 FROM platform.region WHERE administrative_level='TOWNSHIP') THEN
    RAISE EXCEPTION 'Registration reference catalog is missing; do not expose an empty enrollment form';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM platform.identity_provider_binding b
      JOIN platform.security_user u ON u.subject_id=b.security_subject_id
      JOIN platform.security_user_role r ON r.subject_id=u.subject_id
      WHERE u.subject_id='admin' AND u.enabled AND r.role_code='SYSTEM_ADMIN') THEN
    RAISE EXCEPTION 'Approved admin identity binding is missing; do not route admin through employee registration';
  END IF;
END $$;
