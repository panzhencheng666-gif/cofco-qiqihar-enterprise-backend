CREATE FUNCTION production.require_production_object_type()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM platform.object_type type
        WHERE type.code = NEW.object_type_code AND type.business_domain = 'PRODUCTION'
    ) THEN
        RAISE EXCEPTION 'Object type % is not configured for production monitoring', NEW.object_type_code;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER production_record_requires_production_object_type
BEFORE INSERT OR UPDATE OF object_type_code ON production.production_record
FOR EACH ROW EXECUTE FUNCTION production.require_production_object_type();
