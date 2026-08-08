package com.cofco.qiqihar.graintrade.shared.security.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cofco.qiqihar.graintrade.shared.application.AuthenticationRequiredException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AccessControlTest {

    @Test
    void readAuthenticationConfigurationCannotDisableApplicationAuthorization() {
        AccessControl accessControl = new AccessControl(
                Optional::<String>empty,
                subjectId -> Optional.empty(),
                false);

        assertThatThrownBy(accessControl::requireReadScope)
                .isInstanceOf(AuthenticationRequiredException.class);
    }
}
