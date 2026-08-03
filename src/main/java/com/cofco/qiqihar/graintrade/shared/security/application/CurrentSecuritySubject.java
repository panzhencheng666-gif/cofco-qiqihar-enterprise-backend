package com.cofco.qiqihar.graintrade.shared.security.application;

import java.util.Optional;

public interface CurrentSecuritySubject {
    Optional<String> subjectId();
}
