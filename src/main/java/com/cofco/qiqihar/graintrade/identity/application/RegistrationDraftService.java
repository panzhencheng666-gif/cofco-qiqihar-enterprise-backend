package com.cofco.qiqihar.graintrade.identity.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/** Verified registration intent stays in the same browser session through the OIDC callback. */
@Service
public class RegistrationDraftService {
    private static final String KEY = RegistrationDraftService.class.getName();
    private final EmployeeRegistrationService employees;
    private final PhoneIdentityService phones;
    private final SmsChallengeService sms;
    public RegistrationDraftService(EmployeeRegistrationService employees, PhoneIdentityService phones, SmsChallengeService sms) {
        this.employees=employees; this.phones=phones; this.sms=sms;
    }
    public void prepare(HttpSession session, String username, String displayName, String unit,
            List<String> regions, String phone, UUID challenge, String code) {
        if (regions != null && regions.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        var assignment = new EmployeeAssignment(username,unit,"ACTIVE","ACTIVE",
                List.of("BUSINESS_OPERATOR"),List.of(),List.of());
        employees.validateDraft(username, assignment);
        phones.requireUnboundPhone(phone);
        synchronized (session) {
            Draft previous = (Draft) session.getAttribute(KEY);
            boolean reusable = previous != null && previous.expiresAt().isAfter(Instant.now())
                    && previous.username().equals(username) && previous.phone().equals(phone);
            if (!reusable && !phone.equals(sms.verify(challenge,code,"REGISTER",session.getId()))) throw invalid();
            session.removeAttribute(KEY+".error");
            session.setAttribute(KEY, new Draft(username,phone,assignment,
                    reusable ? previous.expiresAt() : Instant.now().plusSeconds(600)));
        }
    }
    public void failed(HttpSession session,String message) { session.setAttribute(KEY+".error",message); }
    public String error(HttpSession session) { return java.util.Objects.toString(session.getAttribute(KEY+".error"),""); }
    public java.util.Map<String,Object> state(HttpSession session) {
        Draft draft=(Draft)session.getAttribute(KEY);
        if(draft==null || !draft.expiresAt().isAfter(Instant.now())) return java.util.Map.of();
        return java.util.Map.of("username",draft.username(),"phone",draft.phone(),
                "workUnitCode",draft.assignment().workUnitCode(),"regionCodes",draft.assignment().regionCodes(),
                "displayName",draft.assignment().displayName());
    }
    public boolean complete(HttpSession session, OidcUser user) {
        return complete(session,user,true);
    }
    public boolean completeAuthenticated(HttpSession session, OidcUser user) {
        return complete(session,user,false);
    }
    private boolean complete(HttpSession session, OidcUser user, boolean matchPhoneClaim) {
        if (session == null) return false;
        synchronized (session) {
            Draft draft = (Draft) session.getAttribute(KEY);
            if (draft == null) return false;
            if (!draft.expiresAt().isAfter(Instant.now())
                    || !draft.username().equals(user.getPreferredUsername())
                    || (matchPhoneClaim && !draft.phone().equals(user.getClaimAsString("phone_number")))) {
                session.removeAttribute(KEY);
                throw invalid();
            }
            phones.register(user.getIssuer().toString(),user.getSubject(),draft.username(),draft.assignment(),draft.phone());
            session.removeAttribute(KEY);
            return true;
        }
    }
    private record Draft(String username,String phone,EmployeeAssignment assignment,Instant expiresAt) {}
    private static ClientRequestException invalid() {
        return new ClientRequestException("REGISTRATION_DRAFT_INVALID","注册信息已变化或验证已过期，请重新注册验证");
    }
}
