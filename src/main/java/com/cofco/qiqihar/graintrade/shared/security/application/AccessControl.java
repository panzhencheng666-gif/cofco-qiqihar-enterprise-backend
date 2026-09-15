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

    /** Business data is shared independently of task and responsibility assignments. */
    @Transactional(readOnly = true)
    public AuthorizedReadScope requireBusinessReadScope() {
        SecurityPrincipal principal = require("BUSINESS_READ", null);
        return new AuthorizedReadScope(principal.subjectId(), java.util.Set.of("*"));
    }

    @Transactional(readOnly = true)
    public AuthorizedReadScope requireTaskReadScope() {
        SecurityPrincipal principal = require("BUSINESS_READ", null);
        return new AuthorizedReadScope(principal.subjectId(), principal.isRootAdministrator()
                ? java.util.Set.of("*") : principal.regionCodes());
    }

    @Transactional(readOnly = true)
    public SecurityPrincipal requireAdministrator() {
        SecurityPrincipal principal = requireAuthenticated();
        if (!principal.isRootAdministrator()) {
            throw new AccessDeniedException("ADMINISTRATOR_REQUIRED", "仅管理员和管理员权限账号可维护此类信息");
        }
        return principal;
    }

    /** Map visibility is shared by enabled accounts. */
    @Transactional(readOnly = true)
    public AuthorizedReadScope requireOverviewReadScope() {
        SecurityPrincipal principal = requireAuthenticated();
        return new AuthorizedReadScope(principal.subjectId(), java.util.Set.of("*"));
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
        if (!SecurityPrincipal.isSharedReportingPermission(permissionCode)
                && regionCode != null && !regionCode.isBlank() && !principal.includesRegion(regionCode)) {
            throw new AccessDeniedException("ACCESS_REGION_DENIED", "Data region is outside the assigned scope");
        }
        return principal;
    }

    /** Voiding keeps the stored update permission and the original responsibility scope. */
    @Transactional(readOnly = true)
    public SecurityPrincipal requireBusinessVoid(String regionCode) {
        SecurityPrincipal principal = requireAuthenticated();
        if (!canVoidBusinessRecord(principal, regionCode)) {
            throw new AccessDeniedException("ACCESS_VOID_DENIED", "无权作废该地区记录");
        }
        return principal;
    }

    public boolean canVoidBusinessRecord(SecurityPrincipal principal, String regionCode) {
        if (principal.isRootAdministrator()) return true;
        if (!principal.permissionCodes().contains("BUSINESS_UPDATE")
                || regionCode == null || regionCode.isBlank() || !principal.includesRegion(regionCode)) return false;
        return principals.responsibleSubject(regionCode, false)
                .map(principal.subjectId()::equals).orElse(true);
    }

    /** County reporting is available to every enabled authenticated account. */
    public void requireCountyReporter(SecurityPrincipal principal, String regionCode) {
        requireAuthenticated();
    }
}
