package com.cofco.qiqihar.graintrade.identity.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.security.application.RegistrationDraftCompletion;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Verified registration intent stays in the same browser session through the OIDC callback. */
@Service
public class RegistrationDraftService implements RegistrationDraftCompletion {
    private static final String KEY = RegistrationDraftService.class.getName();
    private final EmployeeRegistrationService employees;
    private final PhoneIdentityService phones;
    private final SmsChallengeService sms;
    private final EmailIdentityService emails;
    private final EmailChallengeService emailChallenges;
    public RegistrationDraftService(EmployeeRegistrationService employees, PhoneIdentityService phones,
            SmsChallengeService sms,EmailIdentityService emails,EmailChallengeService emailChallenges) {
        this.employees=employees; this.phones=phones; this.sms=sms;
        this.emails=emails;this.emailChallenges=emailChallenges;
    }
    public void prepare(HttpSession session, String username, String displayName, String unit,
            List<String> regions, String phone,String email,String verificationMethod,UUID challenge, String code) {
        if (regions != null && regions.stream().anyMatch(java.util.Objects::isNull)) throw invalid();
        String method=verificationMethod;
        if(!"PHONE".equals(method)&&!"EMAIL".equals(method))throw invalid();
        String normalizedEmail=EmailChallengeService.normalize(email);
        var assignment = new EmployeeAssignment(username,unit,"ACTIVE","ACTIVE",
                List.of("BUSINESS_OPERATOR"),List.of(),List.of());
        employees.validateDraft(username, assignment);
        emails.requireAvailable(normalizedEmail);
        if("PHONE".equals(method))phones.requireUnboundPhone(phone);
        synchronized (session) {
            Draft previous = (Draft) session.getAttribute(KEY);
            boolean reusable = previous != null && previous.expiresAt().isAfter(Instant.now())
                    && previous.username().equals(username) && previous.email().equals(normalizedEmail)
                    && previous.verificationMethod().equals(method)
                    && java.util.Objects.equals(previous.phone(),phone);
            if (!reusable) {
                String verified="PHONE".equals(method)
                        ? sms.verify(challenge,code,"REGISTER",session.getId())
                        : emailChallenges.verify(challenge,code,"REGISTER",session.getId());
                if(("PHONE".equals(method)&&!java.util.Objects.equals(phone,verified))
                        ||("EMAIL".equals(method)&&!normalizedEmail.equals(verified)))throw invalid();
            }
            session.removeAttribute(KEY+".error");
            session.setAttribute(KEY, new Draft(username,"PHONE".equals(method)?phone:null,
                    normalizedEmail,method,assignment,
                    reusable ? previous.expiresAt() : Instant.now().plusSeconds(600)));
        }
    }
    @Override public void failed(HttpSession session,String message) { session.setAttribute(KEY+".error",message); }
    public String error(HttpSession session) { return java.util.Objects.toString(session.getAttribute(KEY+".error"),""); }
    public java.util.Map<String,Object> state(HttpSession session) {
        Draft draft=(Draft)session.getAttribute(KEY);
        if(draft==null || !draft.expiresAt().isAfter(Instant.now())) return java.util.Map.of();
        var state=new java.util.LinkedHashMap<String,Object>();
        state.put("username",draft.username());state.put("email",draft.email());
        state.put("verificationMethod",draft.verificationMethod());
        if(draft.phone()!=null)state.put("phone",draft.phone());
        state.put("workUnitCode",draft.assignment().workUnitCode());state.put("regionCodes",draft.assignment().regionCodes());
        state.put("displayName",draft.assignment().displayName());return state;
    }
    @Override @Transactional public boolean complete(HttpSession session, OidcUser user) {
        return complete(session,user,true);
    }
    @Transactional public boolean completeAuthenticated(HttpSession session, OidcUser user) {
        return complete(session,user,false);
    }
    private boolean complete(HttpSession session, OidcUser user, boolean matchPhoneClaim) {
        if (session == null) return false;
        synchronized (session) {
            Draft draft = (Draft) session.getAttribute(KEY);
            if (draft == null) return false;
            if (!draft.expiresAt().isAfter(Instant.now())
                    || !draft.username().equals(user.getPreferredUsername())
                    || (matchPhoneClaim && "PHONE".equals(draft.verificationMethod())
                        && !draft.phone().equals(user.getClaimAsString("phone_number")))) {
                session.removeAttribute(KEY);
                throw invalid();
            }
            if("PHONE".equals(draft.verificationMethod())) {
                phones.register(user.getIssuer().toString(),user.getSubject(),draft.username(),draft.assignment(),draft.phone());
                emails.bind(draft.username(),draft.email(),false);
            } else {
                emails.register(user.getIssuer().toString(),user.getSubject(),draft.username(),draft.assignment(),draft.email());
            }
            session.removeAttribute(KEY);
            return true;
        }
    }
    private record Draft(String username,String phone,String email,String verificationMethod,
            EmployeeAssignment assignment,Instant expiresAt) {}
    private static ClientRequestException invalid() {
        return new ClientRequestException("REGISTRATION_DRAFT_INVALID","注册信息已变化或验证已过期，请重新注册验证");
    }
}
