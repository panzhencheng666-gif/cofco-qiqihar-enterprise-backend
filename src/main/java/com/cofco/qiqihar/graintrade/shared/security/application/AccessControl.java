package com.cofco.qiqihar.graintrade.shared.security.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccessControl {
    private final CurrentSecuritySubject currentSubject;
    private final SecurityPrincipalRepository principals;

    public AccessControl(CurrentSecuritySubject currentSubject, SecurityPrincipalRepository principals,
            @Value("${qiqihar.security.require-read-authentication:true}") boolean readAuthenticationRequired) {
        this.currentSubject = currentSubject;
        this.principals = principals;
    }

    @Transactional(readOnly = true)
    public AuthorizedReadScope requireReadScope() {
        SecurityPrincipal principal = require("BUSINESS_READ", null);
        return new AuthorizedReadScope(principal.subjectId(), principal.regionCodes());
    }

    @Transactional(readOnly = true)
    public SecurityPrincipal requireAuthenticated() {
        String subjectId = currentSubject.subjectId().orElseThrow(AuthenticationRequiredException::new);
        return currentSubject.cachedPrincipal(subjectId).orElseGet(() -> {
            SecurityPrincipal principal = principals.findEnabled(subjectId)
                    .orElseThrow(() -> new AccessDeniedException(
                            "ACCESS_SUBJECT_UNKNOWN", "Access subject is not authorized"));
            currentSubject.cachePrincipal(principal);
            return principal;
        });
    }

    @Transactional(readOnly = true)
    public Optional<SecurityPrincipal> authenticated() {
        return currentSubject.subjectId().flatMap(principals::findEnabled);
    }

    @Transactional(readOnly = true)
    public SecurityPrincipal require(String permissionCode, String regionCode) {
        SecurityPrincipal principal = requireAuthenticated();
        if (!principal.permits(permissionCode)) {
            throw new AccessDeniedException("ACCESS_PERMISSION_DENIED", "Operation permission is denied");
        }
        if (regionCode != null && !regionCode.isBlank() && !principal.includesRegion(regionCode)) {
            throw new AccessDeniedException("ACCESS_REGION_DENIED", "Data region is outside the assigned scope");
        }
        if (regionCode != null && !regionCode.isBlank()
                && (permissionCode.equals("BUSINESS_CREATE") || permissionCode.equals("BUSINESS_UPDATE"))) {
            requireResponsible(principal,regionCode,false);
        }
        return principal;
    }

    public void requireCountyReporter(SecurityPrincipal principal,String regionCode) {
        requireResponsible(principal,regionCode,true);
    }

    private void requireResponsible(SecurityPrincipal principal,String regionCode,boolean countyReporting) {
        var owner=principals.responsibleSubject(regionCode,countyReporting);
        if(owner.isPresent() && !owner.get().equals(principal.subjectId())
                && !principal.permits("FORMAL_SAMPLE_MANAGE")) {
            throw new AccessDeniedException("REGION_RESPONSIBILITY_DENIED",
                    "该地区由指定负责人填报；整县分属多人时请由县级管理员办理地区填报");
        }
    }
}
