package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    private final AccessControl accessControl;

    public SessionController(AccessControl accessControl) {
        this.accessControl = accessControl;
    }

    @GetMapping("/api/v1/session/me")
    ApiResponse<SessionResponse> currentSession() {
        return new ApiResponse<>(SessionResponse.from(accessControl.requireAuthenticated()));
    }

    record SessionResponse(
            String subjectId, String displayName, String workUnitCode,
            List<String> permissions, List<String> regionCodes) {
        static SessionResponse from(SecurityPrincipal principal) {
            return new SessionResponse(
                    principal.subjectId(), principal.displayName(), principal.workUnitCode(),
                    principal.permissionCodes().stream().sorted().toList(),
                    principal.regionCodes().stream().sorted().toList());
        }
    }
}
