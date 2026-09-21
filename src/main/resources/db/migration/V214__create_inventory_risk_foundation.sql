CREATE SCHEMA risk;

REVOKE ALL ON SCHEMA risk FROM PUBLIC;
GRANT USAGE ON SCHEMA risk TO qiqihar_enterprise_runtime;

CREATE TABLE risk.inventory_source (
    source_code varchar(80) PRIMARY KEY,
    source_name varchar(200) NOT NULL,
    facility_code varchar(60) NOT NULL
        REFERENCES overview.storage_facility(facility_code),
    source_type varchar(30) NOT NULL
        CHECK (source_type IN ('WMS','WEIGHBRIDGE','ERP','GATE','MANUAL_APPROVED')),
    connection_mode varchar(30) NOT NULL
        CHECK (connection_mode IN ('WEBHOOK','POLLING','CDC','MESSAGE_QUEUE','FILE')),
    trust_level varchar(30) NOT NULL
        CHECK (trust_level IN ('AUTHORITATIVE','CORROBORATING')),
    status_code varchar(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status_code IN ('DRAFT','ACTIVE','SUSPENDED','RETIRED')),
    source_timezone varchar(60) NOT NULL DEFAULT 'Asia/Shanghai',
    maximum_receive_delay_seconds integer NOT NULL
        CHECK (maximum_receive_delay_seconds > 0),
    expected_heartbeat_seconds integer
        CHECK (expected_heartbeat_seconds IS NULL OR expected_heartbeat_seconds > 0),
    last_heartbeat_at timestamptz,
    created_by_subject varchar(160) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by_subject varchar(160) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (source_code,facility_code)
);

COMMENT ON TABLE risk.inventory_source IS
    'Governed inventory data sources. Credentials and private signing keys are stored outside this table.';

CREATE TABLE risk.inventory_source_event (
    source_event_id uuid PRIMARY KEY,
    source_code varchar(80) NOT NULL,
    external_event_id varchar(160) NOT NULL,
    source_sequence bigint,
    event_type varchar(30) NOT NULL
        CHECK (event_type IN ('STOCK_IN','STOCK_OUT','TRANSFER','COUNT_ADJUSTMENT','REVERSAL')),
    facility_code varchar(60) NOT NULL
        REFERENCES overview.storage_facility(facility_code),
    warehouse_code varchar(100) NOT NULL,
    target_facility_code varchar(60)
        REFERENCES overview.storage_facility(facility_code),
    target_warehouse_code varchar(100),
    cargo_owner_code varchar(100) NOT NULL,
    batch_code varchar(120) NOT NULL,
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    grade_code varchar(80) NOT NULL DEFAULT 'UNSPECIFIED',
    crop_year integer CHECK (crop_year IS NULL OR crop_year BETWEEN 1900 AND 2200),
    document_reference varchar(160),
    original_quantity numeric(24,6) NOT NULL CHECK (original_quantity > 0),
    original_unit varchar(20) NOT NULL
        CHECK (original_unit IN ('TONNE','KILOGRAM','GRAM')),
    standard_quantity_tonnes numeric(24,6) NOT NULL
        CHECK (standard_quantity_tonnes > 0),
    conversion_rule_version varchar(40) NOT NULL,
    gross_weight_tonnes numeric(24,6) CHECK (gross_weight_tonnes IS NULL OR gross_weight_tonnes >= 0),
    tare_weight_tonnes numeric(24,6) CHECK (tare_weight_tonnes IS NULL OR tare_weight_tonnes >= 0),
    net_weight_tonnes numeric(24,6) CHECK (net_weight_tonnes IS NULL OR net_weight_tonnes >= 0),
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    source_timezone varchar(60) NOT NULL,
    source_clock_quality varchar(20) NOT NULL DEFAULT 'UNKNOWN'
        CHECK (source_clock_quality IN ('VERIFIED','UNVERIFIED','UNKNOWN')),
    payload_sha256 char(64) NOT NULL,
    signature_status varchar(20) NOT NULL
        CHECK (signature_status IN ('VERIFIED','NOT_CONFIGURED','FAILED')),
    processing_status varchar(30) NOT NULL DEFAULT 'RECEIVED'
        CHECK (processing_status IN ('RECEIVED','QUARANTINED','POSTED','DUPLICATE')),
    quarantine_reason varchar(120),
    posted_at timestamptz,
    UNIQUE (source_code, external_event_id),
    UNIQUE (source_code, source_sequence),
    FOREIGN KEY (source_code,facility_code)
        REFERENCES risk.inventory_source(source_code,facility_code),
    CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK ((processing_status='QUARANTINED') = (quarantine_reason IS NOT NULL)),
    CHECK ((processing_status='POSTED') = (posted_at IS NOT NULL)),
    CHECK (signature_status<>'FAILED' OR processing_status='QUARANTINED'),
    CHECK ((target_facility_code IS NULL) = (target_warehouse_code IS NULL)),
    CHECK ((event_type IN ('TRANSFER','REVERSAL')) OR target_facility_code IS NULL),
    CHECK ((original_unit='TONNE' AND standard_quantity_tonnes=original_quantity)
        OR (original_unit='KILOGRAM'
            AND standard_quantity_tonnes=round(original_quantity/1000,6))
        OR (original_unit='GRAM'
            AND standard_quantity_tonnes=round(original_quantity/1000000,6))),
    CHECK (received_at >= occurred_at - interval '24 hours')
);

CREATE INDEX inventory_source_event_received_idx
    ON risk.inventory_source_event(received_at DESC,source_code);
CREATE INDEX inventory_source_event_processing_idx
    ON risk.inventory_source_event(processing_status,received_at);

CREATE TABLE risk.inventory_movement (
    movement_id uuid PRIMARY KEY,
    source_event_id uuid NOT NULL
        REFERENCES risk.inventory_source_event(source_event_id),
    movement_leg varchar(20) NOT NULL
        CHECK (movement_leg IN ('SINGLE','SOURCE','TARGET')),
    movement_type varchar(30) NOT NULL
        CHECK (movement_type IN ('STOCK_IN','STOCK_OUT','TRANSFER','COUNT_ADJUSTMENT','REVERSAL')),
    facility_code varchar(60) NOT NULL
        REFERENCES overview.storage_facility(facility_code),
    warehouse_code varchar(100) NOT NULL,
    cargo_owner_code varchar(100) NOT NULL,
    batch_code varchar(120) NOT NULL,
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    grade_code varchar(80) NOT NULL,
    crop_year integer CHECK (crop_year IS NULL OR crop_year BETWEEN 1900 AND 2200),
    quantity_delta_tonnes numeric(24,6) NOT NULL CHECK (quantity_delta_tonnes <> 0),
    reversal_of_movement_id uuid REFERENCES risk.inventory_movement(movement_id),
    occurred_at timestamptz NOT NULL,
    posted_at timestamptz NOT NULL,
    posted_by_subject varchar(160) NOT NULL,
    UNIQUE (source_event_id, movement_leg),
    UNIQUE (reversal_of_movement_id),
    CHECK ((movement_type='STOCK_IN' AND quantity_delta_tonnes > 0)
        OR (movement_type='STOCK_OUT' AND quantity_delta_tonnes < 0)
        OR movement_type IN ('TRANSFER','COUNT_ADJUSTMENT','REVERSAL')),
    CHECK ((movement_type='REVERSAL') = (reversal_of_movement_id IS NOT NULL)),
    CHECK ((movement_type IN ('STOCK_IN','STOCK_OUT','COUNT_ADJUSTMENT') AND movement_leg='SINGLE')
        OR movement_type IN ('TRANSFER','REVERSAL')),
    CHECK (posted_at >= occurred_at)
);

CREATE INDEX inventory_movement_balance_rebuild_idx ON risk.inventory_movement(
    facility_code,warehouse_code,cargo_owner_code,batch_code,product_code,grade_code,crop_year,
    posted_at,movement_id
);

CREATE TABLE risk.inventory_balance (
    balance_id uuid PRIMARY KEY,
    facility_code varchar(60) NOT NULL
        REFERENCES overview.storage_facility(facility_code),
    warehouse_code varchar(100) NOT NULL,
    cargo_owner_code varchar(100) NOT NULL,
    batch_code varchar(120) NOT NULL,
    product_code varchar(40) NOT NULL REFERENCES platform.product(code),
    grade_code varchar(80) NOT NULL,
    crop_year integer CHECK (crop_year IS NULL OR crop_year BETWEEN 1900 AND 2200),
    quantity_tonnes numeric(24,6) NOT NULL CHECK (quantity_tonnes >= 0),
    last_movement_id uuid NOT NULL REFERENCES risk.inventory_movement(movement_id),
    version bigint NOT NULL CHECK (version >= 0),
    updated_at timestamptz NOT NULL,
    UNIQUE NULLS NOT DISTINCT (
        facility_code,warehouse_code,cargo_owner_code,batch_code,
        product_code,grade_code,crop_year
    )
);

CREATE TABLE risk.risk_rule_set_version (
    rule_set_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    domain_code varchar(30) NOT NULL
        CHECK (domain_code IN ('INVENTORY','MARKET','SUPPLY','LOGISTICS','QUALITY','OPERATIONS','DATA_PIPELINE')),
    rule_set_name varchar(200) NOT NULL,
    status_code varchar(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status_code IN ('DRAFT','REVIEW_PENDING','APPROVED','ACTIVE','RETIRED')),
    scope_definition jsonb NOT NULL,
    rule_definition jsonb NOT NULL,
    definition_sha256 char(64) NOT NULL,
    missing_data_policy varchar(30) NOT NULL
        CHECK (missing_data_policy IN ('QUARANTINE','MANUAL_REVIEW','NO_DECISION')),
    late_event_policy varchar(30) NOT NULL
        CHECK (late_event_policy IN ('REPLAY','MANUAL_REVIEW','NO_DECISION')),
    created_by_subject varchar(160) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    approved_by_subject varchar(160),
    approved_at timestamptz,
    effective_from timestamptz,
    effective_to timestamptz,
    PRIMARY KEY (rule_set_id,version),
    UNIQUE (rule_set_id,version,domain_code),
    CHECK (definition_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK ((status_code IN ('APPROVED','ACTIVE','RETIRED'))
        = (approved_by_subject IS NOT NULL AND approved_at IS NOT NULL)),
    CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from),
    CHECK (jsonb_typeof(scope_definition)='object'),
    CHECK (jsonb_typeof(rule_definition)='object')
);

CREATE UNIQUE INDEX risk_rule_set_one_active_version
    ON risk.risk_rule_set_version(rule_set_id) WHERE status_code='ACTIVE';

CREATE TABLE risk.risk_assessment (
    assessment_id uuid PRIMARY KEY,
    domain_code varchar(30) NOT NULL
        CHECK (domain_code IN ('INVENTORY','MARKET','SUPPLY','LOGISTICS','QUALITY','OPERATIONS','DATA_PIPELINE')),
    subject_type varchar(60) NOT NULL,
    subject_id varchar(160) NOT NULL,
    source_event_id uuid REFERENCES risk.inventory_source_event(source_event_id),
    rule_set_id uuid,
    rule_set_version integer,
    model_id uuid,
    model_version integer,
    evaluation_mode varchar(20) NOT NULL
        CHECK (evaluation_mode IN ('RULE','MODEL_SHADOW','MODEL_GOVERNED','AI_ASSISTED')),
    risk_level varchar(20) NOT NULL
        CHECK (risk_level IN ('NONE','LOW','MEDIUM','HIGH','CRITICAL','UNAVAILABLE')),
    reason_codes varchar(80)[] NOT NULL,
    evidence_snapshot jsonb NOT NULL,
    score numeric(12,8),
    evaluated_at timestamptz NOT NULL,
    evaluation_duration_ms integer NOT NULL CHECK (evaluation_duration_ms >= 0),
    FOREIGN KEY (rule_set_id,rule_set_version,domain_code)
        REFERENCES risk.risk_rule_set_version(rule_set_id,version,domain_code),
    UNIQUE (assessment_id,model_id,model_version),
    CHECK ((rule_set_id IS NULL) = (rule_set_version IS NULL)),
    CHECK ((model_id IS NULL) = (model_version IS NULL)),
    CHECK (evaluation_mode='RULE' OR model_id IS NOT NULL),
    CHECK (jsonb_typeof(evidence_snapshot)='object')
);

CREATE INDEX risk_assessment_subject_idx
    ON risk.risk_assessment(domain_code,subject_type,subject_id,evaluated_at DESC);

CREATE TABLE risk.risk_case_feedback (
    feedback_id uuid PRIMARY KEY,
    assessment_id uuid NOT NULL REFERENCES risk.risk_assessment(assessment_id),
    conclusion_code varchar(30) NOT NULL
        CHECK (conclusion_code IN ('CONFIRMED','FALSE_POSITIVE','MISSED_RISK','INSUFFICIENT_EVIDENCE')),
    reason_code varchar(80) NOT NULL,
    disposition_note text NOT NULL,
    resolved_by_subject varchar(160) NOT NULL,
    resolved_at timestamptz NOT NULL,
    UNIQUE (assessment_id)
);

CREATE TABLE risk.ai_model (
    model_id uuid PRIMARY KEY,
    model_code varchar(100) NOT NULL UNIQUE,
    model_name varchar(200) NOT NULL,
    model_kind varchar(30) NOT NULL
        CHECK (model_kind IN ('DOMAIN_LLM','ANOMALY','FORECAST','RERANKER')),
    domain_code varchar(30) NOT NULL
        CHECK (domain_code IN ('CROSS_DOMAIN','INVENTORY','MARKET','SUPPLY','LOGISTICS','QUALITY','OPERATIONS','DATA_PIPELINE')),
    isolation_scope varchar(30) NOT NULL DEFAULT 'RISK_SYSTEM'
        CHECK (isolation_scope='RISK_SYSTEM'),
    base_model_reference text NOT NULL,
    purpose_definition jsonb NOT NULL,
    status_code varchar(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status_code IN ('DRAFT','ACTIVE','SUSPENDED','RETIRED')),
    created_by_subject varchar(160) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by_subject varchar(160) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (model_id,domain_code),
    CHECK (jsonb_typeof(purpose_definition)='object')
);

CREATE TABLE risk.ai_training_policy (
    training_policy_id uuid PRIMARY KEY,
    model_id uuid NOT NULL REFERENCES risk.ai_model(model_id),
    frequency_code varchar(20) NOT NULL DEFAULT 'DAILY'
        CHECK (frequency_code='DAILY'),
    scheduled_local_time time NOT NULL,
    schedule_timezone varchar(60) NOT NULL DEFAULT 'Asia/Shanghai',
    training_window_days integer NOT NULL CHECK (training_window_days > 0),
    minimum_new_labels integer NOT NULL CHECK (minimum_new_labels >= 0),
    automatic_candidate_enabled boolean NOT NULL DEFAULT true,
    auto_activation_enabled boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT false,
    approved_by_subject varchar(160) NOT NULL,
    approved_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (model_id),
    CHECK (NOT auto_activation_enabled)
);

CREATE TABLE risk.knowledge_snapshot (
    knowledge_snapshot_id uuid PRIMARY KEY,
    snapshot_date date NOT NULL,
    cutoff_at timestamptz NOT NULL,
    content_sha256 char(64) NOT NULL,
    embedding_model_reference text NOT NULL,
    source_manifest jsonb NOT NULL,
    document_count bigint NOT NULL CHECK (document_count >= 0),
    chunk_count bigint NOT NULL CHECK (chunk_count >= 0),
    status_code varchar(20) NOT NULL
        CHECK (status_code IN ('FROZEN','REJECTED','ACTIVE','RETIRED')),
    rejection_reason varchar(200),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (snapshot_date,content_sha256),
    CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (jsonb_typeof(source_manifest)='object'),
    CHECK ((status_code='REJECTED') = (rejection_reason IS NOT NULL))
);

CREATE TABLE risk.training_snapshot (
    training_snapshot_id uuid PRIMARY KEY,
    domain_code varchar(30) NOT NULL
        CHECK (domain_code IN ('CROSS_DOMAIN','INVENTORY','MARKET','SUPPLY','LOGISTICS','QUALITY','OPERATIONS','DATA_PIPELINE')),
    snapshot_date date NOT NULL,
    cutoff_at timestamptz NOT NULL,
    knowledge_snapshot_id uuid REFERENCES risk.knowledge_snapshot(knowledge_snapshot_id),
    data_sha256 char(64) NOT NULL,
    feature_schema_version varchar(80) NOT NULL,
    row_count bigint NOT NULL CHECK (row_count >= 0),
    positive_label_count bigint NOT NULL CHECK (positive_label_count >= 0),
    negative_label_count bigint NOT NULL CHECK (negative_label_count >= 0),
    source_watermarks jsonb NOT NULL,
    status_code varchar(20) NOT NULL
        CHECK (status_code IN ('FROZEN','REJECTED','USED')),
    rejection_reason varchar(200),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (training_snapshot_id,domain_code),
    UNIQUE (domain_code,snapshot_date,data_sha256),
    CHECK (data_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (jsonb_typeof(source_watermarks)='object'),
    CHECK ((status_code='REJECTED') = (rejection_reason IS NOT NULL))
);

CREATE TABLE risk.training_run (
    training_run_id uuid PRIMARY KEY,
    model_id uuid NOT NULL REFERENCES risk.ai_model(model_id),
    training_snapshot_id uuid NOT NULL,
    domain_code varchar(30) NOT NULL
        CHECK (domain_code IN ('CROSS_DOMAIN','INVENTORY','MARKET','SUPPLY','LOGISTICS','QUALITY','OPERATIONS','DATA_PIPELINE')),
    training_kind varchar(30) NOT NULL
        CHECK (training_kind IN ('RETRAIN','INCREMENTAL','LORA_ADAPTER','CALIBRATION','KNOWLEDGE_ONLY')),
    algorithm_code varchar(100) NOT NULL,
    algorithm_version varchar(80) NOT NULL,
    code_sha256 char(64) NOT NULL,
    parameter_definition jsonb NOT NULL,
    random_seed bigint NOT NULL,
    status_code varchar(30) NOT NULL
        CHECK (status_code IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','REJECTED')),
    started_at timestamptz,
    completed_at timestamptz,
    failure_code varchar(80),
    failure_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (training_run_id,model_id,domain_code),
    FOREIGN KEY (model_id,domain_code) REFERENCES risk.ai_model(model_id,domain_code),
    FOREIGN KEY (training_snapshot_id,domain_code)
        REFERENCES risk.training_snapshot(training_snapshot_id,domain_code),
    CHECK (code_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (jsonb_typeof(parameter_definition)='object'),
    CHECK ((status_code='FAILED') = (failure_code IS NOT NULL)),
    CHECK ((status_code='QUEUED' AND started_at IS NULL AND completed_at IS NULL)
        OR (status_code='RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL)
        OR (status_code='SUCCEEDED' AND started_at IS NOT NULL AND completed_at IS NOT NULL)
        OR (status_code='FAILED' AND started_at IS NOT NULL AND completed_at IS NOT NULL)
        OR (status_code='REJECTED' AND completed_at IS NOT NULL)),
    CHECK (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
);

CREATE TABLE risk.model_version (
    model_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    domain_code varchar(30) NOT NULL
        CHECK (domain_code IN ('CROSS_DOMAIN','INVENTORY','MARKET','SUPPLY','LOGISTICS','QUALITY','OPERATIONS','DATA_PIPELINE')),
    training_run_id uuid NOT NULL,
    status_code varchar(20) NOT NULL DEFAULT 'CANDIDATE'
        CHECK (status_code IN ('CANDIDATE','SHADOW','APPROVED','ACTIVE','REJECTED','RETIRED')),
    artifact_reference text NOT NULL,
    artifact_sha256 char(64) NOT NULL,
    metric_definition jsonb NOT NULL,
    threshold_definition jsonb NOT NULL,
    shadow_started_at timestamptz,
    shadow_completed_at timestamptz,
    approved_by_subject varchar(160),
    approved_at timestamptz,
    activated_at timestamptz,
    retired_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (model_id,version),
    UNIQUE (model_id,version,domain_code),
    FOREIGN KEY (model_id,domain_code) REFERENCES risk.ai_model(model_id,domain_code),
    FOREIGN KEY (training_run_id,model_id,domain_code)
        REFERENCES risk.training_run(training_run_id,model_id,domain_code),
    CHECK (artifact_sha256 ~ '^[0-9a-f]{64}$'),
    CHECK (jsonb_typeof(metric_definition)='object'),
    CHECK (jsonb_typeof(threshold_definition)='object'),
    CHECK (shadow_completed_at IS NULL OR shadow_started_at IS NOT NULL),
    CHECK (shadow_completed_at IS NULL OR shadow_completed_at >= shadow_started_at),
    CHECK ((approved_by_subject IS NULL) = (approved_at IS NULL)),
    CHECK (status_code NOT IN ('APPROVED','ACTIVE','RETIRED') OR approved_at IS NOT NULL),
    CHECK (status_code NOT IN ('CANDIDATE','SHADOW') OR approved_at IS NULL),
    CHECK ((status_code IN ('ACTIVE','RETIRED')) = (activated_at IS NOT NULL)),
    CHECK ((status_code='RETIRED') = (retired_at IS NOT NULL))
);

CREATE UNIQUE INDEX model_version_one_active_per_model
    ON risk.model_version(model_id) WHERE status_code='ACTIVE';

CREATE TABLE risk.model_evaluation (
    model_id uuid NOT NULL,
    model_version integer NOT NULL,
    evaluation_window_start timestamptz NOT NULL,
    evaluation_window_end timestamptz NOT NULL,
    cohort_definition jsonb NOT NULL,
    metric_definition jsonb NOT NULL,
    passed boolean NOT NULL,
    evaluated_at timestamptz NOT NULL,
    PRIMARY KEY (model_id,model_version,evaluation_window_start,evaluation_window_end),
    FOREIGN KEY (model_id,model_version) REFERENCES risk.model_version(model_id,version),
    CHECK (evaluation_window_end > evaluation_window_start),
    CHECK (evaluated_at >= evaluation_window_end),
    CHECK (jsonb_typeof(cohort_definition)='object'),
    CHECK (jsonb_typeof(metric_definition)='object')
);

ALTER TABLE risk.risk_assessment
    ADD CONSTRAINT risk_assessment_model_version_fk
    FOREIGN KEY (model_id,model_version)
    REFERENCES risk.model_version(model_id,version);

CREATE TABLE risk.ai_judgement (
    judgement_id uuid PRIMARY KEY,
    assessment_id uuid NOT NULL,
    model_id uuid NOT NULL,
    model_version integer NOT NULL,
    knowledge_snapshot_id uuid REFERENCES risk.knowledge_snapshot(knowledge_snapshot_id),
    independent_conclusion text NOT NULL,
    supporting_evidence jsonb NOT NULL,
    contradicting_evidence jsonb NOT NULL,
    uncertainty_definition jsonb NOT NULL,
    recommended_actions jsonb NOT NULL,
    confidence numeric(7,6) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    advisory_only boolean NOT NULL DEFAULT true,
    generated_at timestamptz NOT NULL,
    FOREIGN KEY (assessment_id,model_id,model_version)
        REFERENCES risk.risk_assessment(assessment_id,model_id,model_version),
    CHECK (jsonb_typeof(supporting_evidence)='array'),
    CHECK (jsonb_typeof(contradicting_evidence)='array'),
    CHECK (jsonb_typeof(uncertainty_definition)='object'),
    CHECK (jsonb_typeof(recommended_actions)='array'),
    CHECK (advisory_only)
);

CREATE FUNCTION risk.validate_risk_assessment_model_scope()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    version_domain varchar(30);
    version_status varchar(20);
    rule_status varchar(20);
BEGIN
    IF NEW.model_id IS NULL THEN
        IF NEW.evaluation_mode<>'RULE' THEN
            RAISE EXCEPTION 'Model-backed assessments require a model version';
        END IF;
        IF NEW.rule_set_id IS NULL THEN
            RAISE EXCEPTION 'RULE assessments require a rule set version';
        END IF;
        SELECT status_code INTO STRICT rule_status
        FROM risk.risk_rule_set_version
        WHERE rule_set_id=NEW.rule_set_id AND version=NEW.rule_set_version
          AND domain_code=NEW.domain_code;
        IF rule_status<>'ACTIVE' THEN
            RAISE EXCEPTION 'RULE assessments require an ACTIVE rule set version';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.evaluation_mode='RULE' THEN
        RAISE EXCEPTION 'RULE assessments cannot claim a model version';
    END IF;
    SELECT domain_code,status_code INTO STRICT version_domain,version_status
    FROM risk.model_version
    WHERE model_id=NEW.model_id AND version=NEW.model_version;
    IF version_domain<>NEW.domain_code AND version_domain<>'CROSS_DOMAIN' THEN
        RAISE EXCEPTION 'Assessment domain must match the model domain or use CROSS_DOMAIN';
    END IF;
    IF NEW.evaluation_mode='MODEL_SHADOW' AND version_status<>'SHADOW' THEN
        RAISE EXCEPTION 'MODEL_SHADOW assessments require a SHADOW model version';
    END IF;
    IF NEW.evaluation_mode IN ('MODEL_GOVERNED','AI_ASSISTED')
       AND version_status<>'ACTIVE' THEN
        RAISE EXCEPTION 'Governed model assessments require an ACTIVE model version';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER risk_assessment_model_scope_validated
BEFORE INSERT OR UPDATE ON risk.risk_assessment
FOR EACH ROW EXECUTE FUNCTION risk.validate_risk_assessment_model_scope();

CREATE FUNCTION risk.enforce_ai_model_transition()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status_code<>'DRAFT' THEN
            RAISE EXCEPTION 'AI model identities must be created as DRAFT';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status_code<>'DRAFT' AND ROW(
        OLD.model_id,OLD.model_code,OLD.model_kind,OLD.domain_code,OLD.isolation_scope,
        OLD.base_model_reference,OLD.purpose_definition,OLD.created_by_subject,OLD.created_at
    ) IS DISTINCT FROM ROW(
        NEW.model_id,NEW.model_code,NEW.model_kind,NEW.domain_code,NEW.isolation_scope,
        NEW.base_model_reference,NEW.purpose_definition,NEW.created_by_subject,NEW.created_at
    ) THEN
        RAISE EXCEPTION 'Active AI model identity and purpose are immutable';
    END IF;
    IF OLD.status_code=NEW.status_code THEN
        RETURN NEW;
    END IF;
    IF OLD.status_code='DRAFT' AND NEW.status_code<>'ACTIVE'
       OR OLD.status_code='ACTIVE' AND NEW.status_code NOT IN ('SUSPENDED','RETIRED')
       OR OLD.status_code='SUSPENDED' AND NEW.status_code NOT IN ('ACTIVE','RETIRED')
       OR OLD.status_code='RETIRED' THEN
        RAISE EXCEPTION 'Invalid AI model lifecycle transition from % to %',
            OLD.status_code,NEW.status_code;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER ai_model_governed_transition
BEFORE INSERT OR UPDATE ON risk.ai_model
FOR EACH ROW EXECUTE FUNCTION risk.enforce_ai_model_transition();

CREATE FUNCTION risk.validate_inventory_movement()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    source_event risk.inventory_source_event%ROWTYPE;
    original_movement risk.inventory_movement%ROWTYPE;
BEGIN
    SELECT * INTO STRICT source_event
    FROM risk.inventory_source_event
    WHERE source_event_id=NEW.source_event_id;

    IF source_event.processing_status<>'POSTED' THEN
        RAISE EXCEPTION 'Only POSTED source events can create inventory movements';
    END IF;
    IF source_event.event_type<>NEW.movement_type THEN
        RAISE EXCEPTION 'Movement type must match its source event';
    END IF;
    IF abs(NEW.quantity_delta_tonnes)<>source_event.standard_quantity_tonnes
       OR NEW.occurred_at<>source_event.occurred_at THEN
        RAISE EXCEPTION 'Movement quantity and occurrence time must match immutable source evidence';
    END IF;
    IF ROW(
        source_event.cargo_owner_code,source_event.batch_code,source_event.product_code,
        source_event.grade_code,source_event.crop_year
    ) IS DISTINCT FROM ROW(
        NEW.cargo_owner_code,NEW.batch_code,NEW.product_code,NEW.grade_code,NEW.crop_year
    ) OR (NEW.movement_leg='TARGET' AND ROW(
        source_event.target_facility_code,source_event.target_warehouse_code
    ) IS DISTINCT FROM ROW(NEW.facility_code,NEW.warehouse_code))
      OR (NEW.movement_leg<>'TARGET' AND ROW(
        source_event.facility_code,source_event.warehouse_code
    ) IS DISTINCT FROM ROW(NEW.facility_code,NEW.warehouse_code)) THEN
        RAISE EXCEPTION 'Movement dimensions must match immutable source evidence';
    END IF;

    IF NEW.movement_type='TRANSFER' THEN
        IF (NEW.movement_leg='SOURCE' AND NEW.quantity_delta_tonnes>=0)
           OR (NEW.movement_leg='TARGET' AND NEW.quantity_delta_tonnes<=0)
           OR NEW.movement_leg='SINGLE' THEN
            RAISE EXCEPTION 'TRANSFER requires a negative SOURCE leg and positive TARGET leg';
        END IF;
    ELSIF NEW.movement_type='REVERSAL' THEN
        SELECT * INTO STRICT original_movement
        FROM risk.inventory_movement
        WHERE movement_id=NEW.reversal_of_movement_id;
        IF original_movement.movement_type='REVERSAL' THEN
            RAISE EXCEPTION 'A reversal cannot reverse another reversal';
        END IF;
        IF NEW.occurred_at<=original_movement.occurred_at
           OR NEW.posted_at<=original_movement.posted_at THEN
            RAISE EXCEPTION 'REVERSAL must occur and post after the referenced movement';
        END IF;
        IF NEW.movement_leg<>original_movement.movement_leg
           OR NEW.quantity_delta_tonnes<>-original_movement.quantity_delta_tonnes
           OR ROW(
                NEW.facility_code,NEW.warehouse_code,NEW.cargo_owner_code,NEW.batch_code,
                NEW.product_code,NEW.grade_code,NEW.crop_year
              ) IS DISTINCT FROM ROW(
                original_movement.facility_code,original_movement.warehouse_code,
                original_movement.cargo_owner_code,original_movement.batch_code,
                original_movement.product_code,original_movement.grade_code,
                original_movement.crop_year
              ) THEN
            RAISE EXCEPTION 'REVERSAL must exactly negate the referenced movement';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER inventory_movement_validated
BEFORE INSERT ON risk.inventory_movement
FOR EACH ROW EXECUTE FUNCTION risk.validate_inventory_movement();

CREATE FUNCTION risk.validate_inventory_movement_pair()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    movement_count integer;
    source_count integer;
    target_count integer;
    net_quantity numeric(24,6);
    dimension_count integer;
    location_count integer;
    reversed_event_count integer;
BEGIN
    IF NEW.movement_type='TRANSFER' THEN
        SELECT count(*),count(*) FILTER (WHERE movement_leg='SOURCE'),
               count(*) FILTER (WHERE movement_leg='TARGET'),sum(quantity_delta_tonnes),
               count(DISTINCT ROW(cargo_owner_code,batch_code,product_code,grade_code,crop_year)),
               count(DISTINCT ROW(facility_code,warehouse_code))
        INTO movement_count,source_count,target_count,net_quantity,dimension_count,location_count
        FROM risk.inventory_movement
        WHERE source_event_id=NEW.source_event_id AND movement_type='TRANSFER';
        IF movement_count<>2 OR source_count<>1 OR target_count<>1
           OR net_quantity<>0 OR dimension_count<>1 OR location_count<>2 THEN
            RAISE EXCEPTION 'TRANSFER must contain one balanced SOURCE/TARGET pair';
        END IF;
    ELSIF NEW.movement_type='REVERSAL' THEN
        SELECT count(DISTINCT original.source_event_id)
        INTO reversed_event_count
        FROM risk.inventory_movement reversal
        JOIN risk.inventory_movement original
          ON original.movement_id=reversal.reversal_of_movement_id
        WHERE reversal.source_event_id=NEW.source_event_id
          AND original.movement_type='TRANSFER';
        IF reversed_event_count>0 THEN
            SELECT count(*),count(*) FILTER (WHERE movement_leg='SOURCE'),
                   count(*) FILTER (WHERE movement_leg='TARGET')
            INTO movement_count,source_count,target_count
            FROM risk.inventory_movement
            WHERE source_event_id=NEW.source_event_id AND movement_type='REVERSAL';
            IF reversed_event_count<>1 OR movement_count<>2 OR source_count<>1 OR target_count<>1 THEN
                RAISE EXCEPTION 'A transferred inventory event must be reversed as a complete pair';
            END IF;
        END IF;
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER inventory_movement_pair_validated
AFTER INSERT ON risk.inventory_movement
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION risk.validate_inventory_movement_pair();

CREATE FUNCTION risk.reject_inventory_movement_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Inventory movements are immutable; post a reversal event instead';
END
$$;

CREATE TRIGGER inventory_movement_immutable
BEFORE UPDATE OR DELETE ON risk.inventory_movement
FOR EACH ROW EXECUTE FUNCTION risk.reject_inventory_movement_mutation();

CREATE FUNCTION risk.protect_inventory_source_event_evidence()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(
        OLD.source_event_id,OLD.source_code,OLD.external_event_id,OLD.source_sequence,
        OLD.event_type,OLD.facility_code,OLD.warehouse_code,
        OLD.target_facility_code,OLD.target_warehouse_code,OLD.cargo_owner_code,
        OLD.batch_code,OLD.product_code,OLD.grade_code,OLD.crop_year,OLD.document_reference,
        OLD.original_quantity,OLD.original_unit,OLD.standard_quantity_tonnes,
        OLD.conversion_rule_version,OLD.gross_weight_tonnes,OLD.tare_weight_tonnes,
        OLD.net_weight_tonnes,OLD.occurred_at,OLD.received_at,OLD.source_timezone,
        OLD.source_clock_quality,OLD.payload_sha256,OLD.signature_status
    ) IS DISTINCT FROM ROW(
        NEW.source_event_id,NEW.source_code,NEW.external_event_id,NEW.source_sequence,
        NEW.event_type,NEW.facility_code,NEW.warehouse_code,
        NEW.target_facility_code,NEW.target_warehouse_code,NEW.cargo_owner_code,
        NEW.batch_code,NEW.product_code,NEW.grade_code,NEW.crop_year,NEW.document_reference,
        NEW.original_quantity,NEW.original_unit,NEW.standard_quantity_tonnes,
        NEW.conversion_rule_version,NEW.gross_weight_tonnes,NEW.tare_weight_tonnes,
        NEW.net_weight_tonnes,NEW.occurred_at,NEW.received_at,NEW.source_timezone,
        NEW.source_clock_quality,NEW.payload_sha256,NEW.signature_status
    ) THEN
        RAISE EXCEPTION 'Inventory source evidence is immutable after receipt';
    END IF;
    IF OLD.processing_status<>NEW.processing_status AND (
        OLD.processing_status IN ('POSTED','DUPLICATE')
        OR OLD.processing_status='QUARANTINED'
        OR OLD.processing_status='RECEIVED'
           AND NEW.processing_status NOT IN ('QUARANTINED','POSTED','DUPLICATE')
    ) THEN
        RAISE EXCEPTION 'Invalid inventory source event lifecycle transition from % to %',
            OLD.processing_status,NEW.processing_status;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER inventory_source_event_evidence_immutable
BEFORE UPDATE ON risk.inventory_source_event
FOR EACH ROW EXECUTE FUNCTION risk.protect_inventory_source_event_evidence();

CREATE FUNCTION risk.validate_model_evaluation()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    version_status varchar(20);
    shadow_started timestamptz;
BEGIN
    SELECT status_code,shadow_started_at INTO STRICT version_status,shadow_started
    FROM risk.model_version
    WHERE model_id=NEW.model_id AND version=NEW.model_version;
    IF version_status<>'SHADOW' OR shadow_started IS NULL THEN
        RAISE EXCEPTION 'Model evaluations can only be recorded during SHADOW';
    END IF;
    IF NEW.evaluation_window_start<shadow_started THEN
        RAISE EXCEPTION 'Model evaluation window must start within the shadow period';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER model_evaluation_shadow_only
BEFORE INSERT ON risk.model_evaluation
FOR EACH ROW EXECUTE FUNCTION risk.validate_model_evaluation();

CREATE FUNCTION risk.enforce_rule_set_version_transition()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status_code<>'DRAFT' THEN
            RAISE EXCEPTION 'Rule set versions must be created as DRAFT';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status_code<>'DRAFT' AND ROW(
        OLD.rule_set_id,OLD.version,OLD.domain_code,OLD.rule_set_name,
        OLD.scope_definition,OLD.rule_definition,OLD.definition_sha256,
        OLD.missing_data_policy,OLD.late_event_policy,OLD.created_by_subject,OLD.created_at
    ) IS DISTINCT FROM ROW(
        NEW.rule_set_id,NEW.version,NEW.domain_code,NEW.rule_set_name,
        NEW.scope_definition,NEW.rule_definition,NEW.definition_sha256,
        NEW.missing_data_policy,NEW.late_event_policy,NEW.created_by_subject,NEW.created_at
    ) THEN
        RAISE EXCEPTION 'Reviewed rule definitions are immutable; create a new version';
    END IF;
    IF (OLD.approved_by_subject IS NOT NULL
            AND OLD.approved_by_subject IS DISTINCT FROM NEW.approved_by_subject)
       OR (OLD.approved_at IS NOT NULL AND OLD.approved_at IS DISTINCT FROM NEW.approved_at)
       OR (OLD.effective_from IS NOT NULL
            AND OLD.effective_from IS DISTINCT FROM NEW.effective_from)
       OR (OLD.effective_to IS NOT NULL AND OLD.effective_to IS DISTINCT FROM NEW.effective_to) THEN
        RAISE EXCEPTION 'Recorded rule lifecycle evidence is immutable';
    END IF;
    IF OLD.status_code=NEW.status_code THEN
        IF OLD.status_code<>'DRAFT' AND ROW(
            OLD.approved_by_subject,OLD.approved_at,OLD.effective_from,OLD.effective_to
        ) IS DISTINCT FROM ROW(
            NEW.approved_by_subject,NEW.approved_at,NEW.effective_from,NEW.effective_to
        ) THEN
            RAISE EXCEPTION 'Reviewed rule lifecycle evidence is immutable';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status_code='DRAFT' AND NEW.status_code<>'REVIEW_PENDING'
       OR OLD.status_code='REVIEW_PENDING' AND NEW.status_code NOT IN ('DRAFT','APPROVED')
       OR OLD.status_code='APPROVED' AND NEW.status_code NOT IN ('ACTIVE','RETIRED')
       OR OLD.status_code='ACTIVE' AND NEW.status_code<>'RETIRED'
       OR OLD.status_code='RETIRED' THEN
        RAISE EXCEPTION 'Invalid rule set lifecycle transition from % to %',OLD.status_code,NEW.status_code;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER rule_set_version_governed_transition
BEFORE INSERT OR UPDATE ON risk.risk_rule_set_version
FOR EACH ROW EXECUTE FUNCTION risk.enforce_rule_set_version_transition();

CREATE FUNCTION risk.enforce_training_run_transition()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    snapshot_status varchar(20);
    knowledge_status varchar(20);
    model_status varchar(20);
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status_code<>'QUEUED' THEN
            RAISE EXCEPTION 'Training runs must be created as QUEUED';
        END IF;
        SELECT snapshot.status_code,knowledge.status_code,model.status_code
        INTO STRICT snapshot_status,knowledge_status,model_status
        FROM risk.training_snapshot snapshot
        JOIN risk.ai_model model
          ON model.model_id=NEW.model_id AND model.domain_code=NEW.domain_code
        LEFT JOIN risk.knowledge_snapshot knowledge
          ON knowledge.knowledge_snapshot_id=snapshot.knowledge_snapshot_id
        WHERE snapshot.training_snapshot_id=NEW.training_snapshot_id
          AND snapshot.domain_code=NEW.domain_code;
        IF snapshot_status='REJECTED'
           OR knowledge_status IN ('REJECTED','RETIRED')
           OR model_status<>'ACTIVE' THEN
            RAISE EXCEPTION 'Training requires an active model and accepted frozen snapshots';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(
        OLD.training_run_id,OLD.model_id,OLD.training_snapshot_id,OLD.domain_code,
        OLD.training_kind,OLD.algorithm_code,OLD.algorithm_version,OLD.code_sha256,
        OLD.parameter_definition,OLD.random_seed,OLD.created_at
    ) IS DISTINCT FROM ROW(
        NEW.training_run_id,NEW.model_id,NEW.training_snapshot_id,NEW.domain_code,
        NEW.training_kind,NEW.algorithm_code,NEW.algorithm_version,NEW.code_sha256,
        NEW.parameter_definition,NEW.random_seed,NEW.created_at
    ) THEN
        RAISE EXCEPTION 'Training identity, inputs, code and parameters are immutable';
    END IF;
    IF (OLD.started_at IS NOT NULL AND OLD.started_at IS DISTINCT FROM NEW.started_at)
       OR (OLD.completed_at IS NOT NULL AND OLD.completed_at IS DISTINCT FROM NEW.completed_at)
       OR (OLD.failure_code IS NOT NULL AND OLD.failure_code IS DISTINCT FROM NEW.failure_code)
       OR (OLD.failure_message IS NOT NULL
            AND OLD.failure_message IS DISTINCT FROM NEW.failure_message) THEN
        RAISE EXCEPTION 'Recorded training lifecycle evidence is immutable';
    END IF;
    IF OLD.status_code=NEW.status_code THEN
        IF ROW(
            OLD.started_at,OLD.completed_at,OLD.failure_code,OLD.failure_message
        ) IS DISTINCT FROM ROW(
            NEW.started_at,NEW.completed_at,NEW.failure_code,NEW.failure_message
        ) THEN
            RAISE EXCEPTION 'Training lifecycle evidence changes require a state transition';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status_code='QUEUED' AND NEW.status_code NOT IN ('RUNNING','REJECTED')
       OR OLD.status_code='RUNNING' AND NEW.status_code NOT IN ('SUCCEEDED','FAILED','REJECTED')
       OR OLD.status_code IN ('SUCCEEDED','FAILED','REJECTED') THEN
        RAISE EXCEPTION 'Invalid training lifecycle transition from % to %',OLD.status_code,NEW.status_code;
    END IF;
    IF NEW.status_code='RUNNING' AND NEW.started_at IS NULL THEN
        RAISE EXCEPTION 'RUNNING training requires started_at';
    END IF;
    IF NEW.status_code IN ('SUCCEEDED','FAILED','REJECTED') AND NEW.completed_at IS NULL THEN
        RAISE EXCEPTION 'Terminal training requires completed_at';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER training_run_governed_transition
BEFORE INSERT OR UPDATE ON risk.training_run
FOR EACH ROW EXECUTE FUNCTION risk.enforce_training_run_transition();

CREATE FUNCTION risk.enforce_model_version_transition()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    training_status varchar(30);
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status_code<>'CANDIDATE' THEN
            RAISE EXCEPTION 'Model versions must be created as CANDIDATE';
        END IF;
        IF NEW.shadow_started_at IS NOT NULL OR NEW.shadow_completed_at IS NOT NULL
           OR NEW.approved_at IS NOT NULL OR NEW.activated_at IS NOT NULL
           OR NEW.retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'Model candidates cannot prefill lifecycle evidence';
        END IF;
        SELECT status_code INTO STRICT training_status
        FROM risk.training_run
        WHERE training_run_id=NEW.training_run_id
          AND model_id=NEW.model_id AND domain_code=NEW.domain_code;
        IF training_status<>'SUCCEEDED' THEN
            RAISE EXCEPTION 'Model candidates require a SUCCEEDED training run';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(
        OLD.model_id,OLD.version,OLD.domain_code,OLD.training_run_id,
        OLD.artifact_reference,OLD.artifact_sha256,OLD.metric_definition,
        OLD.threshold_definition,OLD.created_at
    ) IS DISTINCT FROM ROW(
        NEW.model_id,NEW.version,NEW.domain_code,NEW.training_run_id,
        NEW.artifact_reference,NEW.artifact_sha256,NEW.metric_definition,
        NEW.threshold_definition,NEW.created_at
    ) THEN
        RAISE EXCEPTION 'Model identity, lineage and artifacts are immutable';
    END IF;
    IF (OLD.shadow_started_at IS NOT NULL
            AND OLD.shadow_started_at IS DISTINCT FROM NEW.shadow_started_at)
       OR (OLD.shadow_completed_at IS NOT NULL
            AND OLD.shadow_completed_at IS DISTINCT FROM NEW.shadow_completed_at)
       OR (OLD.approved_by_subject IS NOT NULL
            AND OLD.approved_by_subject IS DISTINCT FROM NEW.approved_by_subject)
       OR (OLD.approved_at IS NOT NULL AND OLD.approved_at IS DISTINCT FROM NEW.approved_at)
       OR (OLD.activated_at IS NOT NULL AND OLD.activated_at IS DISTINCT FROM NEW.activated_at)
       OR (OLD.retired_at IS NOT NULL AND OLD.retired_at IS DISTINCT FROM NEW.retired_at) THEN
        RAISE EXCEPTION 'Recorded model lifecycle evidence is immutable';
    END IF;
    IF OLD.status_code = NEW.status_code THEN
        IF ROW(
            OLD.shadow_started_at,OLD.shadow_completed_at,OLD.approved_by_subject,
            OLD.approved_at,OLD.activated_at,OLD.retired_at
        ) IS DISTINCT FROM ROW(
            NEW.shadow_started_at,NEW.shadow_completed_at,NEW.approved_by_subject,
            NEW.approved_at,NEW.activated_at,NEW.retired_at
        ) THEN
            RAISE EXCEPTION 'Model lifecycle evidence changes require a state transition';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status_code='CANDIDATE' AND NEW.status_code NOT IN ('SHADOW','REJECTED') THEN
        RAISE EXCEPTION 'CANDIDATE models must enter SHADOW before approval';
    END IF;
    IF NEW.status_code='SHADOW' AND NEW.shadow_started_at IS NULL THEN
        RAISE EXCEPTION 'SHADOW models require shadow_started_at';
    END IF;
    IF OLD.status_code='SHADOW' AND NEW.status_code NOT IN ('APPROVED','REJECTED') THEN
        RAISE EXCEPTION 'SHADOW models must be approved or rejected';
    END IF;
    IF NEW.status_code='APPROVED'
       AND (NEW.shadow_started_at IS NULL OR NEW.shadow_completed_at IS NULL) THEN
        RAISE EXCEPTION 'Model approval requires a completed shadow evaluation';
    END IF;
    IF NEW.status_code='APPROVED' AND NOT EXISTS (
        SELECT 1 FROM risk.model_evaluation evaluation
        WHERE evaluation.model_id=NEW.model_id
          AND evaluation.model_version=NEW.version
          AND evaluation.passed
          AND evaluation.evaluation_window_start>=NEW.shadow_started_at
          AND evaluation.evaluation_window_end<=NEW.shadow_completed_at
    ) THEN
        RAISE EXCEPTION 'Model approval requires a passed recorded evaluation';
    END IF;
    IF NEW.status_code='ACTIVE' AND OLD.status_code<>'APPROVED' THEN
        RAISE EXCEPTION 'Only APPROVED models can become ACTIVE';
    END IF;
    IF NEW.status_code='ACTIVE' AND NEW.activated_at IS NULL THEN
        RAISE EXCEPTION 'ACTIVE models require activated_at';
    END IF;
    IF OLD.status_code='APPROVED' AND NEW.status_code NOT IN ('ACTIVE','REJECTED') THEN
        RAISE EXCEPTION 'APPROVED models must be activated or rejected';
    END IF;
    IF OLD.status_code='ACTIVE' AND NEW.status_code<>'RETIRED' THEN
        RAISE EXCEPTION 'ACTIVE models can only be retired';
    END IF;
    IF NEW.status_code='RETIRED' AND NEW.retired_at IS NULL THEN
        RAISE EXCEPTION 'RETIRED models require retired_at';
    END IF;
    IF OLD.status_code IN ('REJECTED','RETIRED') THEN
        RAISE EXCEPTION 'Terminal model versions cannot transition';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER model_version_governed_transition
BEFORE INSERT OR UPDATE ON risk.model_version
FOR EACH ROW EXECUTE FUNCTION risk.enforce_model_version_transition();

REVOKE ALL ON ALL TABLES IN SCHEMA risk FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA risk FROM PUBLIC;

GRANT SELECT,INSERT,UPDATE ON TABLE
    risk.inventory_source,
    risk.inventory_source_event,
    risk.inventory_balance,
    risk.risk_rule_set_version,
    risk.ai_model,
    risk.ai_training_policy,
    risk.training_run,
    risk.model_version
TO qiqihar_enterprise_runtime;

GRANT SELECT,INSERT ON TABLE
    risk.risk_assessment,
    risk.risk_case_feedback,
    risk.knowledge_snapshot,
    risk.training_snapshot,
    risk.model_evaluation,
    risk.ai_judgement
TO qiqihar_enterprise_runtime;

GRANT SELECT,INSERT ON TABLE risk.inventory_movement TO qiqihar_enterprise_runtime;
GRANT EXECUTE ON FUNCTION
    risk.reject_inventory_movement_mutation(),
    risk.protect_inventory_source_event_evidence(),
    risk.enforce_model_version_transition()
TO qiqihar_enterprise_runtime;

COMMENT ON TABLE risk.inventory_source_event IS
    'Source-faithful event envelope. Duplicate delivery is identified by source code and external event id.';
COMMENT ON TABLE risk.inventory_movement IS
    'Immutable normalized inventory ledger in tonnes; corrections are new reversal movements.';
COMMENT ON TABLE risk.inventory_balance IS
    'Rebuildable current projection. It is never evidence without its underlying movements.';
COMMENT ON TABLE risk.risk_rule_set_version IS
    'Versioned, reviewable business-specific rules; executable scripts and arbitrary SQL are not stored here.';
COMMENT ON TABLE risk.training_snapshot IS
    'Immutable daily training lineage built only from governed business facts and resolved feedback.';
COMMENT ON TABLE risk.ai_model IS
    'Independent model identities dedicated to the risk system; base models are adapted rather than treated as business authority.';
COMMENT ON TABLE risk.ai_training_policy IS
    'Approved daily self-training policy. It may create candidates but can never activate them automatically.';
COMMENT ON TABLE risk.knowledge_snapshot IS
    'Frozen governed knowledge used for retrieval and reproducible AI evaluation.';
COMMENT ON TABLE risk.model_version IS
    'Candidate models must pass shadow evaluation and explicit approval before activation.';
COMMENT ON TABLE risk.ai_judgement IS
    'Evidence-bound independent assessment with counter-evidence and uncertainty; always advisory and never an autonomous business action.';
