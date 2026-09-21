package com.cofco.qiqihar.graintrade.identity.application;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
class RegistrationDraftServiceTest {
    final EmployeeRegistrationService employees=mock(EmployeeRegistrationService.class);
    final PhoneIdentityService phones=mock(PhoneIdentityService.class);
    final SmsChallengeService sms=mock(SmsChallengeService.class);
    final EmailIdentityService emails=mock(EmailIdentityService.class);
    final EmailChallengeService emailChallenges=mock(EmailChallengeService.class);
    final RegistrationDraftService service=new RegistrationDraftService(employees,phones,sms,emails,emailChallenges);
    final MockHttpSession session=new MockHttpSession();
    final UUID challenge=UUID.randomUUID();
    final String phone="13900009999";
    final String email="employee@example.com";
    void prepare() {
        when(sms.verify(challenge,"123456","REGISTER",session.getId())).thenReturn(phone);
        service.prepare(session,"employee","员工","UNIT",List.of("region"),phone,email,"PHONE",challenge,"123456");
    }
    OidcUser user(String username,String userPhone) throws Exception {
        var user=mock(OidcUser.class);
        when(user.getIssuer()).thenReturn(new java.net.URL("https://issuer.example.test"));
        when(user.getSubject()).thenReturn("provider-subject");
        when(user.getPreferredUsername()).thenReturn(username);
        when(user.getClaimAsString("phone_number")).thenReturn(userPhone);
        return user;
    }
    @Test void verifiedDraftCompletesOnceAfterMatchingOidcCallback() throws Exception {
        prepare(); var user=user("employee",phone);
        assertTrue(service.complete(session,user)); assertFalse(service.complete(session,user));
        verify(phones).register(eq("https://issuer.example.test"),eq("provider-subject"),eq("employee"),any(),eq(phone));
    }
    @Test void cannotUseAnotherSessionsDraftOrAnotherIdentity() throws Exception {
        prepare(); assertFalse(service.complete(new MockHttpSession(),user("employee",phone)));
        assertThrows(RuntimeException.class,()->service.complete(session,user("admin",phone)));
        verify(phones,never()).register(any(),any(),any(),any(),any());
    }
    @Test void changedPhoneClaimCannotBindVerifiedPhone() throws Exception {
        prepare(); assertThrows(RuntimeException.class,()->service.complete(session,user("employee","13900008888")));
        verify(phones,never()).register(any(),any(),any(),any(),any());
    }
    @Test void nativeValidationRetryReusesProofWithoutReusingSmsCode() {
        prepare(); service.prepare(session,"employee","员工","UNIT",List.of("region"),phone,email,"PHONE",null,"");
        verify(sms,times(1)).verify(any(),any(),any(),any());
        assertEquals(phone,service.state(session).get("phone"));
    }
    @Test void existingAuthenticatedIdentityCanFinishSameRegistrationForm() throws Exception {
        prepare(); assertTrue(service.completeAuthenticated(session,user("employee","")));
        verify(phones).register(any(),any(),eq("employee"),any(),eq(phone));
    }
    @Test void emptyRegionsProduceOrdinaryUnboundDraft() {
        when(sms.verify(challenge,"123456","REGISTER",session.getId())).thenReturn(phone);
        service.prepare(session,"赵长彬","赵长彬","UNIT",List.of(),phone,"zhao@example.com","PHONE",challenge,"123456");
        var capture=org.mockito.ArgumentCaptor.forClass(EmployeeAssignment.class);
        verify(employees).validateDraft(eq("赵长彬"),capture.capture());
        assertEquals(List.of("BUSINESS_OPERATOR"),capture.getValue().roleCodes());
        assertTrue(capture.getValue().regionCodes().isEmpty());
    }
    @Test void emailVerificationCompletesWithoutCreatingAPhoneLogin() throws Exception {
        when(emailChallenges.verify(challenge,"123456","REGISTER",session.getId())).thenReturn(email);
        service.prepare(session,"employee","员工","UNIT",List.of(),null,email,"EMAIL",challenge,"123456");
        assertTrue(service.completeAuthenticated(session,user("employee","")));
        verify(emails).register(eq("https://issuer.example.test"),eq("provider-subject"),
                eq("employee"),any(),eq(email));
        verify(phones,never()).register(any(),any(),any(),any(),any());
    }
    @Test void invalidRegionsNeverConsumeVerification() {
        assertThrows(RuntimeException.class,()->service.prepare(session,"employee","员工","UNIT",Arrays.asList((String)null),phone,email,"PHONE",challenge,"123456"));
        verifyNoInteractions(sms,phones,emails,emailChallenges);
    }
    @Test void missingVerificationMethodNeverFallsBackToPhone() {
        assertThrows(RuntimeException.class,()->service.prepare(session,"employee","员工","UNIT",
                List.of(),phone,email,null,challenge,"123456"));
        verifyNoInteractions(sms,phones,emails,emailChallenges);
    }
}
