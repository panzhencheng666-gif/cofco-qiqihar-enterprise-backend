package com.cofco.qiqihar.graintrade.shared.security.application;

import com.cofco.qiqihar.graintrade.shared.security.domain.SecurityPrincipal;
import java.util.Optional;

public interface SecurityPrincipalRepository {
    Optional<SecurityPrincipal> findEnabled(String subjectId);
}
