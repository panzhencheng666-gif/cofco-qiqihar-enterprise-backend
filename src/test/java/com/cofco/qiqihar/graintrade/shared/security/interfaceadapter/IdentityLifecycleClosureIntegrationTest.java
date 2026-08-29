package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.bootstrap.GrainTradeApplication;
import com.cofco.qiqihar.graintrade.identity.application.IdentityInvitationTokenCodec;
import com.cofco.qiqihar.graintrade.testsupport.GovernedMasterDataFixtures;
import com.cofco.qiqihar.graintrade.testsupport.UsesProtectedTestDatabase;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class)
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class IdentityLifecycleClosureIntegrationTest {
    private static final String WORK_UNIT = "QIQIHAR_BUSINESS";
    private static final String TOWNSHIP = "230202996";
    private static final String CONTRACT_VERSION = "2026-08-30";

    @Autowired MockMvc mvc;
    @Autowired DataSource dataSource;
    @Autowired IdentityInvitationTokenCodec invitationTokens;
    private JdbcClient jdbc;
    private String subject;

    @BeforeEach
    void prepare() {
        jdbc = JdbcClient.create(dataSource);
        subject="identity-lifecycle-"+UUID.randomUUID();
        deleteSubject();
        jdbc.sql("""
                INSERT INTO platform.work_unit(code,name,sort_order)
                VALUES(:unit,'身份生命周期自动化测试单位',9980)
                ON CONFLICT(code) DO NOTHING
                """).param("unit", WORK_UNIT).update();
        GovernedMasterDataFixtures.insertRegion(
                jdbc, TOWNSHIP, "身份生命周期测试乡镇", "230202", "TOWNSHIP", 9996);
        jdbc.sql("""
                INSERT INTO platform.work_unit_region_scope(work_unit_code,region_code)
                VALUES(:unit,:region),(:unit,'232700') ON CONFLICT DO NOTHING
                """).param("unit", WORK_UNIT).param("region", TOWNSHIP).update();
    }

    @AfterEach
    void cleanup() {
        if (jdbc == null) return;
        deleteSubject();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code=:unit AND region_code=:region")
                .param("unit", WORK_UNIT).param("region", TOWNSHIP).update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code=:unit AND region_code='232700'")
                .param("unit", WORK_UNIT).update();
        GovernedMasterDataFixtures.deleteRegions(jdbc, java.util.List.of(TOWNSHIP));
    }

    @Test
    void invitationIsVersionedIdempotentAndNeverReturnsTheSecretToken() throws Exception {
        String idempotencyKey = "identity-invite-" + UUID.randomUUID();
        String request = invitationRequest("employee@example.test");

        String first = mvc.perform(post("/api/v1/identity/employees")
                        .principal(() -> "production-tester")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contractVersion").value(CONTRACT_VERSION))
                .andExpect(jsonPath("$.data.subjectId").value(subject))
                .andExpect(jsonPath("$.data.accountStatus").value("INVITED"))
                .andExpect(jsonPath("$.data.invitationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.deliveryStatus").value("QUEUED"))
                .andExpect(jsonPath("$.data.invitationId").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String invitationId = com.jayway.jsonpath.JsonPath.read(first, "$.data.invitationId");
        mvc.perform(post("/api/v1/identity/employees")
                        .principal(() -> "production-tester")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invitationId")
                        .value(invitationId))
                .andExpect(jsonPath("$.data.deliveryStatus").value("QUEUED"));
    }

    @Test
    void jagdaqiIsAssignableOnlyAsTheAuthorizedLeafCountyScope() throws Exception {
        mvc.perform(get("/api/v1/identity/employees/assignment-options")
                        .param("workUnitCode", WORK_UNIT)
                        .principal(() -> "production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.regionCodes[?(@ == '232761')]").exists());
    }

    @Test
    void activationBindsOnlyTheIssuerAndSubjectFromTrustedOidcAuthentication() throws Exception {
        String activationToken = "not-a-real-token-yet";
        mvc.perform(post("/api/v1/identity/invitations/activate")
                        .with(oidcLogin().idToken(token -> token
                                .claim(IdTokenClaimNames.ISS, "https://idp.example.test/realms/cofco")
                                .claim(IdTokenClaimNames.SUB, "provider-subject-001")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + activationToken + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDENTITY_INVITATION_INVALID"))
                .andExpect(jsonPath("$.error.message").value("邀请凭证无效或已失效"));
    }

    @Test
    void unknownExpiredAndRevokedInvitationTokensHaveOneNonDisclosingError() throws Exception {
        for (String token : new String[]{"unknown-token", "expired-token", "revoked-token"}) {
            mvc.perform(post("/api/v1/identity/invitations/activate")
                            .with(oidcLogin().idToken(idToken -> idToken
                                    .claim(IdTokenClaimNames.ISS, "https://idp.example.test/realms/cofco")
                                    .claim(IdTokenClaimNames.SUB, "provider-subject-001")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"" + token + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("IDENTITY_INVITATION_INVALID"))
                    .andExpect(jsonPath("$.error.message").value("邀请凭证无效或已失效"));
        }
    }

    @Test
    void deliveredSecretActivatesExactlyOnceAndBindsTheTrustedOidcIdentity() throws Exception {
        String response=invite("activate-once-"+UUID.randomUUID(),"employee@example.test");
        String invitationId=com.jayway.jsonpath.JsonPath.read(response,"$.data.invitationId");
        String encrypted=jdbc.sql("""
                SELECT encrypted_delivery_payload FROM platform.identity_invitation
                WHERE invitation_id=CAST(:id AS uuid)
                """).param("id",invitationId).query(String.class).single();
        var payload=invitationTokens.decryptDeliveryPayload(encrypted);

        org.assertj.core.api.Assertions.assertThat(payload.deliveryAddress())
                .isEqualTo("employee@example.test");
        org.assertj.core.api.Assertions.assertThat(payload.token()).hasSizeGreaterThanOrEqualTo(40);
        org.assertj.core.api.Assertions.assertThat(encrypted)
                .doesNotContain(payload.deliveryAddress()).doesNotContain(payload.token());
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.identity_invitation
                WHERE invitation_id=CAST(:id AS uuid) AND token_hash=:tokenHash
                """).param("id",invitationId).param("tokenHash",invitationTokens.sha256(payload.token()))
                .query(Long.class).single()).isOne();

        activate(payload.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractVersion").value(CONTRACT_VERSION))
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.bindingStatus").value("ACTIVE"));
        activate(payload.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDENTITY_INVITATION_INVALID"));

        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.identity_provider_binding
                WHERE security_subject_id=:subject AND issuer_uri=:issuer
                  AND provider_subject='provider-subject-001' AND state='ACTIVE'
                """).param("subject",subject)
                .param("issuer","https://idp.example.test/realms/cofco")
                .query(Long.class).single()).isOne();
    }

    @Test
    void revokedInvitationCannotActivateAndReissueRotatesTheSecret() throws Exception {
        String first=invite("revoke-first-"+UUID.randomUUID(),"employee@example.test");
        String firstId=com.jayway.jsonpath.JsonPath.read(first,"$.data.invitationId");
        String firstToken=invitationTokens.decryptDeliveryPayload(jdbc.sql("""
                SELECT encrypted_delivery_payload FROM platform.identity_invitation
                WHERE invitation_id=CAST(:id AS uuid)
                """).param("id",firstId).query(String.class).single()).token();

        mvc.perform(post("/api/v1/identity/invitations/{invitationId}/revoke",firstId)
                        .principal(()->"production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invitationStatus").value("REVOKED"));
        activate(firstToken).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDENTITY_INVITATION_INVALID"));

        String reissued=mvc.perform(post("/api/v1/identity/employees/{subjectId}/invitations",subject)
                        .principal(()->"production-tester")
                        .header("Idempotency-Key","reissue-"+UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryAddress\":\"employee@example.test\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.invitationStatus").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String secondId=com.jayway.jsonpath.JsonPath.read(reissued,"$.data.invitationId");
        String secondToken=invitationTokens.decryptDeliveryPayload(jdbc.sql("""
                SELECT encrypted_delivery_payload FROM platform.identity_invitation
                WHERE invitation_id=CAST(:id AS uuid)
                """).param("id",secondId).query(String.class).single()).token();
        org.assertj.core.api.Assertions.assertThat(secondId).isNotEqualTo(firstId);
        org.assertj.core.api.Assertions.assertThat(secondToken).isNotEqualTo(firstToken);
        activate(secondToken).andExpect(status().isOk());
    }

    @Test
    void disableRestoreAndAuthorizationChangeRevokeSharedSessionsImmediately() throws Exception {
        String created=invite("session-revoke-"+UUID.randomUUID(),"employee@example.test");
        String invitationId=com.jayway.jsonpath.JsonPath.read(created,"$.data.invitationId");
        String token=invitationTokens.decryptDeliveryPayload(jdbc.sql("""
                SELECT encrypted_delivery_payload FROM platform.identity_invitation
                WHERE invitation_id=CAST(:id AS uuid)
                """).param("id",invitationId).query(String.class).single()).token();
        activate(token).andExpect(status().isOk());
        String sessionId=UUID.randomUUID().toString();
        long now=System.currentTimeMillis();
        jdbc.sql("""
                INSERT INTO platform.http_session(primary_id,session_id,creation_time,last_access_time,
                    max_inactive_interval,expiry_time,principal_name)
                VALUES(:id,:id,:now,:now,1800,:expiry,:subject)
                """).param("id",sessionId).param("now",now).param("expiry",now+1_800_000)
                .param("subject",subject).update();
        jdbc.sql("""
                INSERT INTO platform.oidc_session_registry(
                    session_id,security_subject_id,issuer_uri,provider_subject,audience,
                    identity_version,expires_at)
                VALUES(:id,:subject,'https://idp.example.test/realms/cofco','provider-subject-001',
                       ARRAY['enterprise'],0,now()+interval '30 minutes')
                """).param("id",sessionId).param("subject",subject).update();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/v1/identity/employees/{subjectId}",subject)
                        .principal(()->"production-tester").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":1,"displayName":"身份生命周期员工",
                                 "workUnitCode":"%s","accountStatus":"SUSPENDED",
                                 "employmentStatus":"ACTIVE","positionCodes":[],
                                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                                """.formatted(WORK_UNIT,TOWNSHIP)))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                "SELECT count(*) FROM platform.http_session WHERE session_id=:id")
                .param("id",sessionId).query(Long.class).single()).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.oidc_session_registry
                WHERE session_id=:id AND revoked_at IS NOT NULL
                  AND revocation_reason='IDENTITY_CHANGED'
                """).param("id",sessionId).query(Long.class).single()).isOne();
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT session_version FROM platform.security_user WHERE subject_id=:subject
                """).param("subject",subject).query(Long.class).single()).isOne();
    }

    private String invitationRequest(String deliveryAddress) {
        return """
                {"subjectId":"%s","displayName":"身份生命周期员工",
                 "deliveryAddress":"%s","workUnitCode":"%s","positionCodes":[],
                 "roleCodes":["BUSINESS_OPERATOR"],"regionCodes":["%s"]}
                """.formatted(subject, deliveryAddress, WORK_UNIT, TOWNSHIP);
    }

    private String invite(String idempotencyKey,String deliveryAddress) throws Exception {
        return mvc.perform(post("/api/v1/identity/employees")
                        .principal(() -> "production-tester")
                        .header("Idempotency-Key",idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invitationRequest(deliveryAddress)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private org.springframework.test.web.servlet.ResultActions activate(String token) throws Exception {
        return mvc.perform(post("/api/v1/identity/invitations/activate")
                .with(oidcLogin().idToken(idToken -> idToken
                        .claim(IdTokenClaimNames.ISS,"https://idp.example.test/realms/cofco")
                        .claim(IdTokenClaimNames.SUB,"provider-subject-001")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\""+token+"\"}"));
    }

    private void deleteSubject() {
        jdbc.sql("DELETE FROM platform.oidc_session_registry WHERE security_subject_id=:subject")
                .param("subject", subject).update();
        jdbc.sql("DELETE FROM platform.identity_delivery_outbox WHERE security_subject_id=:subject")
                .param("subject", subject).update();
        jdbc.sql("DELETE FROM platform.identity_invitation WHERE security_subject_id=:subject")
                .param("subject", subject).update();
        jdbc.sql("DELETE FROM platform.identity_provider_binding WHERE security_subject_id=:subject")
                .param("subject", subject).update();
        jdbc.sql("DELETE FROM platform.security_user_position WHERE subject_id=:subject")
                .param("subject", subject).update();
        jdbc.sql("DELETE FROM platform.security_user_region_scope WHERE subject_id=:subject")
                .param("subject", subject).update();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id=:subject")
                .param("subject", subject).update();
        long immutableAudit=jdbc.sql("""
                SELECT count(*) FROM platform.business_audit_event WHERE actor_subject_id=:subject
                """).param("subject",subject).query(Long.class).single();
        if(immutableAudit==0)jdbc.sql("DELETE FROM platform.security_user WHERE subject_id=:subject")
                .param("subject",subject).update();
    }
}
