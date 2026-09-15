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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = GrainTradeApplication.class, properties = "QIQIHAR_SMS_ENABLED=true")
@AutoConfigureMockMvc
@UsesProtectedTestDatabase
class IdentityLifecycleClosureIntegrationTest {
    private static final String WORK_UNIT = "QIQIHAR_BUSINESS";
    private static final String TOWNSHIP = "230202996";
    private static final String CONTRACT_VERSION = "2026-08-30";

    @Autowired MockMvc mvc;
    @Autowired com.cofco.qiqihar.graintrade.identity.interfaceadapter.PhoneIdentityController phoneController;
    @Autowired com.cofco.qiqihar.graintrade.shared.interfaceadapter.GlobalExceptionHandler exceptionHandler;
    @Autowired com.cofco.qiqihar.graintrade.shared.security.application.SecurityPrincipalRepository principals;
    private MockMvc phoneMvc;
    @Autowired com.cofco.qiqihar.graintrade.identity.application.PhoneIdentityService phoneIdentities;
    @Autowired DataSource dataSource;
    @Autowired IdentityInvitationTokenCodec invitationTokens;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.cofco.qiqihar.graintrade.identity.application.SmsVerificationGateway smsGateway;
    private JdbcClient jdbc;
    private String subject;
    private final java.util.Map<MockHttpSession,MockHttpSession> phoneSessions=new java.util.IdentityHashMap<>();

    @BeforeEach
    void prepare() {
        jdbc = JdbcClient.create(dataSource);
        // The application's general test filter is stateless; exercise the real SMS controller
        // with servlet sessions and real transactional services without that test-only filter.
        phoneMvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(phoneController)
                .setControllerAdvice(exceptionHandler).build();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        org.mockito.Mockito.when(smsGateway.verify(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("123456"),org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        jdbc.sql("DELETE FROM platform.sms_challenge WHERE phone IN ('13900000601','13900000602')").update();
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
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        if (jdbc == null) return;
        deleteSubject();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code=:unit AND region_code=:region")
                .param("unit", WORK_UNIT).param("region", TOWNSHIP).update();
        jdbc.sql("DELETE FROM platform.work_unit_region_scope WHERE work_unit_code=:unit AND region_code='232700'")
                .param("unit", WORK_UNIT).update();
        GovernedMasterDataFixtures.deleteRegions(jdbc, java.util.List.of(TOWNSHIP));
    }

    @Test
    void verifiedPhoneActivatesZeroRegionReporterAndCodeCannotReplay() throws Exception {
        phoneInvite();
        MockHttpSession session=new MockHttpSession();
        String challenge=challenge("13900000601",session);
        login(challenge,"000000",session).andExpect(status().isBadRequest());
        assertAccount("INVITED");
        login(challenge,"123456",session).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subjectId").value(subject));
        assertAccount("ACTIVE");
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("SELECT subject_id FROM platform.phone_identity WHERE phone='13900000601'")
                .query(String.class).single()).isEqualTo(subject);
        var principal=principals.findEnabled(subject).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(principal.isUnassignedReporter()).isTrue();
        org.assertj.core.api.Assertions.assertThat(principal.isRootAdministrator()).isFalse();
        org.assertj.core.api.Assertions.assertThat(principal.permissionCodes()).doesNotContain("IDENTITY_ADMIN");
        login(challenge,"123456",session).andExpect(status().isBadRequest());
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("SELECT count(*) FROM platform.business_audit_event WHERE aggregate_id=:s AND action_code='SECURITY_USER_ACTIVATED'")
                .param("s",subject).query(Long.class).single()).isOne();
    }

    @Test
    void otherPhoneAndOtherSessionCannotClaimInvitation() throws Exception {
        phoneInvite();
        MockHttpSession session=new MockHttpSession();
        String challenge=challenge("13900000601",session);
        login(challenge,"123456",new MockHttpSession()).andExpect(status().isBadRequest());
        login(challenge("13900000602",session),"123456",session).andExpect(status().isForbidden());
        assertAccount("INVITED");
    }

    @Test
    void revokedPhoneInvitationCannotActivate() throws Exception {
        String response=phoneInvite();
        String id=com.jayway.jsonpath.JsonPath.read(response,"$.data.invitationId");
        mvc.perform(post("/api/v1/identity/invitations/{id}/revoke",id).principal(()->"production-tester"))
                .andExpect(status().isOk());
        MockHttpSession session=new MockHttpSession();
        login(challenge("13900000601",session),"123456",session).andExpect(status().isForbidden());
        assertAccount("INVITED");
    }

    @Test
    void expiredInvitationCannotActivate() throws Exception {
        phoneInvite();
        jdbc.sql("UPDATE platform.identity_invitation SET created_at=now()-interval '2 days',expires_at=now()-interval '1 day' WHERE security_subject_id=:s")
                .param("s",subject).update();
        MockHttpSession session=new MockHttpSession();
        login(challenge("13900000601",session),"123456",session).andExpect(status().isForbidden());
        assertAccount("INVITED");
    }

    @Test
    void pendingPhoneCannotBeInvitedForAnotherAccount() throws Exception {
        phoneInvite();
        mvc.perform(post("/api/v1/identity/employees").principal(()->"production-tester")
                .header("Idempotency-Key","duplicate-phone-"+UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(phoneRequest().replace(subject,subject+"-other")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PHONE_ALREADY_BOUND"));
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("SELECT count(*) FROM platform.security_user WHERE subject_id=:s")
                .param("s",subject+"-other").query(Long.class).single()).isZero();
    }

    @Test
    void emailInvitationCanBeExplicitlyReplacedByPhoneButOldTokenCannotActivate() throws Exception {
        invite("legacy-invite-"+UUID.randomUUID(),"legacy@example.test");
        String payload=jdbc.sql("SELECT encrypted_delivery_payload FROM platform.identity_invitation WHERE security_subject_id=:s")
                .param("s",subject).query(String.class).single();
        String oldToken=invitationTokens.decryptDeliveryPayload(payload).token();
        mvc.perform(post("/api/v1/identity/employees/{subject}/invitations",subject)
                .principal(()->"production-tester").header("Idempotency-Key","reissue-phone-"+UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"deliveryAddress\":\"13900000601\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.deliveryStatus").value("AWAITING_VERIFICATION"));
        activate(oldToken).andExpect(status().isBadRequest());
        String phonePayload=jdbc.sql("SELECT encrypted_delivery_payload FROM platform.identity_invitation WHERE security_subject_id=:s AND state='PENDING'")
                .param("s",subject).query(String.class).single();
        activate(invitationTokens.decryptDeliveryPayload(phonePayload).token()).andExpect(status().isBadRequest());
        assertAccount("INVITED");
    }

    @Test
    void pendingInvitationReservesPhoneAgainstRegistrationAndBinding() throws Exception {
        phoneInvite();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->phoneIdentities.requireUnboundPhone("13900000601"))
                .hasMessageContaining("待验证邀请");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->phoneIdentities.bind("production-tester","13900000601"))
                .hasMessageContaining("待验证邀请");
        assertAccount("INVITED");
    }

    @Test
    void activatedPhoneKeepsItsAccountAndCannotBeInvitedAgain() throws Exception {
        phoneInvite();
        org.assertj.core.api.Assertions.assertThat(phoneIdentities.login("13900000601").subject()).isEqualTo(subject);
        org.assertj.core.api.Assertions.assertThat(phoneIdentities.login("13900000601").subject()).isEqualTo(subject);
        mvc.perform(post("/api/v1/identity/employees").principal(()->"production-tester")
                .header("Idempotency-Key","bound-phone-"+UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(phoneRequest().replace(subject,subject+"-other")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PHONE_ALREADY_BOUND"));
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("SELECT count(*) FROM platform.business_audit_event WHERE aggregate_id=:s AND action_code='SECURITY_USER_ACTIVATED'")
                .param("s",subject).query(Long.class).single()).isOne();
    }

    @Test
    void inactiveEmployeeCannotActivateAndNoBindingIsCreated() throws Exception {
        phoneInvite();
        jdbc.sql("UPDATE platform.security_user SET employment_status='LEAVE' WHERE subject_id=:s").param("s",subject).update();
        MockHttpSession session=new MockHttpSession();
        login(challenge("13900000601",session),"123456",session).andExpect(status().isForbidden());
        assertAccount("INVITED");
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("SELECT count(*) FROM platform.phone_identity WHERE subject_id=:s")
                .param("s",subject).query(Long.class).single()).isZero();
    }

    @Test
    void removedRolesRollbackActivation() throws Exception {
        phoneInvite();
        jdbc.sql("DELETE FROM platform.security_user_role WHERE subject_id=:s").param("s",subject).update();
        MockHttpSession session=new MockHttpSession();
        login(challenge("13900000601",session),"123456",session).andExpect(status().isForbidden());
        assertAccount("INVITED");
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("SELECT state FROM platform.identity_invitation WHERE security_subject_id=:s")
                .param("s",subject).query(String.class).single()).isEqualTo("PENDING");
    }

    private String phoneRequest() {
        return invitationRequest("13900000601").replace("[\""+TOWNSHIP+"\"]","[]");
    }
    private String phoneInvite() throws Exception {
        return mvc.perform(post("/api/v1/identity/employees").principal(()->"production-tester")
                .header("Idempotency-Key","phone-invite-"+UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(phoneRequest()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }
    private String challenge(String phone,MockHttpSession session) throws Exception {
        var request=post("/api/v1/identity/phone/challenge").session(phoneSessions.getOrDefault(session,session))
                .contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\""+phone+"\",\"purpose\":\"LOGIN\"}");
        var result=phoneMvc.perform(request).andExpect(status().isOk()).andReturn();
        phoneSessions.put(session,(MockHttpSession)result.getRequest().getSession());
        var response=result.getResponse();
        return com.jayway.jsonpath.JsonPath.read(response.getContentAsString(),"$.data.challengeId");
    }
    private org.springframework.test.web.servlet.ResultActions login(String id,String code,MockHttpSession session) throws Exception {
        var request=post("/api/v1/identity/phone/login").session(phoneSessions.getOrDefault(session,session)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\":\""+id+"\",\"code\":\""+code+"\"}");
        var result=phoneMvc.perform(request);
        phoneSessions.put(session,(MockHttpSession)result.andReturn().getRequest().getSession());
        return result;
    }
    private void assertAccount(String status) {
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("SELECT account_status FROM platform.security_user WHERE subject_id=:s")
                .param("s",subject).query(String.class).single()).isEqualTo(status);
    }

    @Test
    void phoneInvitationWaitsForVerificationWithoutDeliveryOrEarlyBinding() throws Exception {
        String response=invite("phone-invite-"+UUID.randomUUID(),"13900000601");
        org.assertj.core.api.Assertions.assertThat((String)com.jayway.jsonpath.JsonPath.read(
                response,"$.data.deliveryStatus")).isEqualTo("AWAITING_VERIFICATION");
        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                "SELECT count(*) FROM platform.identity_delivery_outbox WHERE security_subject_id=:s")
                .param("s",subject).query(Long.class).single()).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.sql(
                "SELECT count(*) FROM platform.phone_identity WHERE subject_id=:s")
                .param("s",subject).query(Long.class).single()).isZero();
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
                .andExpect(jsonPath("$.data.roles").isArray())
                .andExpect(jsonPath("$.data.positions").isArray())
                .andExpect(jsonPath("$.data.regionCodes").isArray())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(post("/api/v1/identity/employees")
                        .principal(() -> "production-tester")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());

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
    void zeroRegionReporterCanBeInvitedAndActivatedWithoutAdministratorPowers() throws Exception {
        String response=mvc.perform(post("/api/v1/identity/employees")
                .principal(() -> "production-tester")
                .header("Idempotency-Key", "unassigned-"+UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invitationRequest("reporter@example.test").replace(
                        "\"regionCodes\":[\""+TOWNSHIP+"\"]", "\"regionCodes\":[]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.regionCodes").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String invitationId=com.jayway.jsonpath.JsonPath.read(response,"$.data.invitationId");
        String encrypted=jdbc.sql("SELECT encrypted_delivery_payload FROM platform.identity_invitation WHERE invitation_id=CAST(:id AS uuid)")
                .param("id",invitationId).query(String.class).single();
        activate(invitationTokens.decryptDeliveryPayload(encrypted).token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.bindingStatus").value("ACTIVE"));
        mvc.perform(get("/api/v1/session/me").principal(() -> subject))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.regionCodes").isEmpty())
                .andExpect(jsonPath("$.data.unassignedReporter").value(true))
                .andExpect(jsonPath("$.data.rootAdministrator").value(false));
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("SELECT count(*) FROM platform.identity_provider_binding WHERE security_subject_id=:subject AND state='ACTIVE'")
                .param("subject",subject).query(Long.class).single()).isOne();
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
        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT approved_by FROM platform.identity_provider_binding
                WHERE security_subject_id=:subject AND issuer_uri=:issuer
                """).param("subject",subject)
                .param("issuer","https://idp.example.test/realms/cofco")
                .query(String.class).single()).isEqualTo("production-tester");
    }

    @Test
    void successfulActivationInvalidatesTheUnregisteredBootstrapSession() throws Exception {
        String response=invite("activation-session-"+UUID.randomUUID(),"employee@example.test");
        String invitationId=com.jayway.jsonpath.JsonPath.read(response,"$.data.invitationId");
        String token=invitationTokens.decryptDeliveryPayload(jdbc.sql("""
                SELECT encrypted_delivery_payload FROM platform.identity_invitation
                WHERE invitation_id=CAST(:id AS uuid)
                """).param("id",invitationId).query(String.class).single()).token();
        MockHttpSession session=new MockHttpSession();

        activate(token,session).andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void activationRollsBackProviderBindingWhenInvitedAccountStateChangedConcurrently() throws Exception {
        String response=invite("activation-state-race-"+UUID.randomUUID(),"employee@example.test");
        String invitationId=com.jayway.jsonpath.JsonPath.read(response,"$.data.invitationId");
        String token=invitationTokens.decryptDeliveryPayload(jdbc.sql("""
                SELECT encrypted_delivery_payload FROM platform.identity_invitation
                WHERE invitation_id=CAST(:id AS uuid)
                """).param("id",invitationId).query(String.class).single()).token();
        jdbc.sql("""
                UPDATE platform.security_user SET account_status='ACTIVE',enabled=true
                WHERE subject_id=:subject
                """).param("subject",subject).update();

        activate(token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("IDENTITY_INVITATION_INVALID"));

        org.assertj.core.api.Assertions.assertThat(jdbc.sql("""
                SELECT count(*) FROM platform.identity_provider_binding
                WHERE security_subject_id=:subject
                """).param("subject",subject).query(Long.class).single()).isZero();
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
    void currentInvitationCanBeRequeriedWithoutExposingItsSecret() throws Exception {
        String created=invite("current-invitation-"+UUID.randomUUID(),"employee@example.test");
        String invitationId=com.jayway.jsonpath.JsonPath.read(created,"$.data.invitationId");

        mvc.perform(get("/api/v1/identity/employees/{subjectId}/invitation",subject)
                        .principal(()->"production-tester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractVersion").value(CONTRACT_VERSION))
                .andExpect(jsonPath("$.data.invitationId").value(invitationId))
                .andExpect(jsonPath("$.data.invitationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.deliveryStatus").value("QUEUED"))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.activationUrl").doesNotExist());
        mvc.perform(get("/api/v1/identity/employees/{subjectId}/invitation","unknown-subject")
                        .principal(()->"production-tester"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("IDENTITY_SUBJECT_NOT_FOUND"));
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
        return activate(token,null);
    }

    private org.springframework.test.web.servlet.ResultActions activate(
            String token,MockHttpSession session) throws Exception {
        var request=post("/api/v1/identity/invitations/activate")
                .with(oidcLogin().idToken(idToken -> idToken
                        .claim(IdTokenClaimNames.ISS,"https://idp.example.test/realms/cofco")
                        .claim(IdTokenClaimNames.SUB,"provider-subject-001")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\""+token+"\"}");
        if(session!=null)request.session(session);
        return mvc.perform(request);
    }

    private void deleteSubject() {
        jdbc.sql("DELETE FROM platform.phone_identity WHERE subject_id=:subject").param("subject",subject).update();
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
