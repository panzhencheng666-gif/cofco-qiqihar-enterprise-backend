-- These formal object types already occur in approved production, market and
-- logistics records. Expose only their governed semantic icon keys; the API
-- still returns a type only when an actual in-scope formal record uses it.
DO $govern_actual_overview_types$
DECLARE
  governed_type record;
  target_snapshot jsonb;
BEGIN
  FOR governed_type IN
    SELECT * FROM (VALUES
      ('RICE_MILL','rice-mill'),
      ('RAIL_NODE','rail-node'),
      ('ROAD_NODE','road-node')
    ) value(code,icon_key)
  LOOP
    SELECT to_jsonb(object_type)
           || jsonb_build_object(
                'overview_enabled',true,
                'overview_icon_key',governed_type.icon_key)
      INTO target_snapshot
      FROM platform.object_type object_type
     WHERE object_type.code=governed_type.code;
    IF target_snapshot IS NULL THEN
      RAISE EXCEPTION 'Required formal object type % is missing',governed_type.code;
    END IF;
    PERFORM platform.govern_master_data_change(
      'OBJECT_TYPE',governed_type.code,'UPDATE',target_snapshot,clock_timestamp(),
      'V139_OVERVIEW_TYPE_APPLICANT','V139_OVERVIEW_TYPE_INDEPENDENT_REVIEWER',
      'Approved formal records use this type; enable its unique governed overview icon');
  END LOOP;
END;
$govern_actual_overview_types$;
