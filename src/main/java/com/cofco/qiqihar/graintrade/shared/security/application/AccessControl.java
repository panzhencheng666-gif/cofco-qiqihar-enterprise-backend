package com.cofco.qiqihar.graintrade.shared.security.application;

import com.cofco.qiqihar.graintrade.shared.application.AccessDeniedException;
import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
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
        return principals.findEnabled(subjectId)
                .orElseThrow(() -> new AccessDeniedException("ACCESS_SUBJECT_UNKNOWN", "Access subject is not authorized"));
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
        return principal;
    }
}
