package com.cofco.qiqihar.riskintelligence.web;

import com.cofco.qiqihar.graintrade.risk.infrastructure.JdbcRiskWorkbenchRepository;

import com.cofco.qiqihar.graintrade.risk.application.*;
import com.cofco.qiqihar.graintrade.risk.interfaceadapter.RiskWorkbenchController;
import com.cofco.qiqihar.riskintelligence.security.*;
import com.cofco.qiqihar.riskintelligence.web.RiskApiErrorHandler;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Synthetic fixtures only, in a separately provisioned disposable PostgreSQL database. */
@EnabledIfEnvironmentVariable(named="RISK_SCOPE_TEST_DB_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/risk_scope_test")
class JdbcRiskRegionScopeIntegrationTest {
    private JdbcClient jdbc;
    private JdbcRiskWorkbenchRepository repository;
    private MockMvc mvc;
    private UUID allowed, denied, legacy, numeric, descendant;
    private final RiskBusinessSession regional = new RiskBusinessSession("regional", Set.of("BUSINESS_READ", "BUSINESS_UPDATE"), false, Set.of("230221"));
    private final RiskBusinessSession root = new RiskBusinessSession("root", Set.of(), true, Set.of());

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(System.getenv("RISK_SCOPE_TEST_DB_URL"), "scope_test", "");
        jdbc = JdbcClient.create(ds);
        // This database is dedicated to this class. Do not set this variable to an application DB.
        jdbc.sql("DROP SCHEMA IF EXISTS risk CASCADE").update();
        jdbc.sql("CREATE SCHEMA risk").update();
        jdbc.sql("""
                CREATE TABLE risk.ai_model(model_id uuid PRIMARY KEY, model_name text);
                CREATE TABLE risk.risk_assessment(
                  assessment_id uuid PRIMARY KEY, domain_code text, subject_type text, subject_id text,
                  evaluation_mode text, risk_level text, reason_codes text[], score numeric,
                  evaluated_at timestamptz, evaluation_duration_ms integer, model_id uuid, model_version integer,
                  evidence_snapshot jsonb NOT NULL);
                CREATE TABLE risk.risk_case_feedback(
                  feedback_id uuid PRIMARY KEY, assessment_id uuid UNIQUE REFERENCES risk.risk_assessment,
                  conclusion_code text, reason_code text, disposition_note text,
                  resolved_by_subject text, resolved_at timestamptz);
                CREATE TABLE risk.ai_judgement(
                  judgement_id uuid, assessment_id uuid, independent_conclusion text,
                  supporting_evidence jsonb, contradicting_evidence jsonb, uncertainty_definition jsonb,
                  recommended_actions jsonb, confidence numeric, generated_at timestamptz);
                """).update();
        allowed = insert("{\"regionCode\":\"230221\",\"fixture\":true}", 1);
        denied = insert("{\"regionCode\":\"230222\",\"fixture\":true}", 5);
        legacy = insert("{\"fixture\":true}", 4);
        numeric = insert("{\"regionCode\":230221}", 3);
        descendant = insert("{\"regionCode\":\"230221001\"}", 2);
        insert("{\"regionCode\":\"\"}", 6);
        repository = new JdbcRiskWorkbenchRepository(ds, new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new RiskWorkbenchController(new RiskWorkbenchService(repository, Clock.systemUTC())))
                .setControllerAdvice(new RiskApiErrorHandler()).build();
    }

    @Test void listFiltersBeforeLimitAndIgnoresForgedRegionHeaders() throws Exception {
        mvc.perform(get("/api/v1/risk/workbench/assessments").param("status", "ALL").param("limit", "1")
                .requestAttr(RiskBusinessSession.REQUEST_ATTRIBUTE, regional)
                .header("X-Region-Code", "230222").header("X-Region-Codes", "*"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].assessmentId").value(allowed.toString()));
    }

    @Test void detailsHideOtherRegionsLegacyNumericAndDescendantsLikeMissing() throws Exception {
        String missing = detail(UUID.randomUUID(), regional, 404);
        for (UUID id : new UUID[]{denied, legacy, numeric, descendant}) {
            assertThat(detail(id, regional, 404)).isEqualTo(missing);
        }
        detail(allowed, regional, 200);
        detail(legacy, root, 200);
    }

    @Test void feedbackDeniesBeforeStorageAndUsesTrustedActor() throws Exception {
        for (UUID id : new UUID[]{denied, legacy, numeric, descendant, UUID.randomUUID()}) {
            feedback(id, regional, 404);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM risk.risk_case_feedback").query(Integer.class).single()).isZero();
        feedback(allowed, regional, 200);
        assertThat(jdbc.sql("SELECT resolved_by_subject FROM risk.risk_case_feedback").query(String.class).single()).isEqualTo("regional");
        feedback(allowed, regional, 409);
    }

    @Test void emptyScopeIsDeniedEvenWhenCallingControllerWithoutFilter() throws Exception {
        var empty = new RiskBusinessSession("empty", Set.of("BUSINESS_READ"), false, Set.of());
        mvc.perform(get("/api/v1/risk/workbench/assessments").param("status", "ALL")
                .requestAttr(RiskBusinessSession.REQUEST_ATTRIBUTE, empty))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        detail(allowed, empty, 404);
        feedback(allowed, empty, 404);
    }

    @Test void repositoryInsertionCannotBypassScopeAndRootRetainsAllRows() {
        for (UUID id : new UUID[]{denied, legacy, numeric, descendant}) {
            assertThat(repository.assessmentExists(id, regional.scope())).isFalse();
            assertThat(repository.createFeedback(id, "CONFIRMED", "TEST_ONLY", "fixture",
                    "regional", java.time.Instant.now(), regional.scope())).isEmpty();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM risk.risk_case_feedback").query(Integer.class).single()).isZero();
        assertThat(repository.findAssessments(new RiskAssessmentQuery("", "", "ALL", "", 100), root.scope())).hasSize(6);
        assertThat(repository.assessmentExists(legacy, root.scope())).isTrue();
        assertThat(repository.createFeedback(legacy, "CONFIRMED", "TEST_ONLY", "fixture",
                "root", java.time.Instant.now(), root.scope())).isPresent();
    }

    @Test void scopeIsImmutableAndUnscopedWorkbenchOperationsDoNotExist() {
        var regions = new java.util.HashSet<>(Set.of("230221"));
        var scope = new RiskRegionScope(false, regions);
        regions.add("230222");
        assertThat(scope.regionCodes()).containsExactly("230221");
        assertThatThrownBy(() -> scope.regionCodes().add("230222")).isInstanceOf(UnsupportedOperationException.class);
        for (Class<?> type : new Class<?>[]{RiskWorkbenchRepository.class, RiskWorkbenchService.class}) {
            for (var method : type.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                    assertThat(method.getParameterTypes()).contains(RiskRegionScope.class);
                }
            }
        }
    }

    private String detail(UUID id, RiskBusinessSession session, int status) throws Exception {
        String body = mvc.perform(get("/api/v1/risk/workbench/assessments/{id}", id)
                .requestAttr(RiskBusinessSession.REQUEST_ATTRIBUTE, session))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        // Trace IDs are intentionally unique per request; compare the public error content.
        return body.replaceAll("\"traceId\":\"[^\"]*\"", "\"traceId\":\"<request>\"");
    }
    private void feedback(UUID id, RiskBusinessSession session, int status) throws Exception {
        mvc.perform(post("/api/v1/risk/workbench/assessments/{id}/feedback", id)
                .requestAttr(RiskBusinessSession.REQUEST_ATTRIBUTE, session).header("X-Actor", "forged")
                .contentType("application/json")
                .content("{\"conclusionCode\":\"CONFIRMED\",\"reasonCode\":\"TEST_ONLY\",\"dispositionNote\":\"isolated fixture\",\"regionCode\":\"230221\"}"))
                .andExpect(status().is(status));
    }
    private UUID insert(String evidence, int day) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO risk.risk_assessment VALUES(
                  :id,'INVENTORY','TEST_FIXTURE','230221-subject-is-not-authority',
                  'RULE','LOW',ARRAY['TEST_ONLY'],null,
                  CAST(:date AS timestamptz),1,null,null,CAST(:evidence AS jsonb))
                """).param("id", id).param("date", "2026-01-0"+day+"T00:00:00Z")
                .param("evidence", evidence).update();
        return id;
    }
}
