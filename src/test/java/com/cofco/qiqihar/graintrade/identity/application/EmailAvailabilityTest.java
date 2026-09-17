package com.cofco.qiqihar.graintrade.identity.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import com.cofco.qiqihar.graintrade.shared.application.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class EmailAvailabilityTest {
    @Test void disabledDeliveryIsAServiceFailureRatherThanInvalidUserInput() {
        var service=new EmailChallengeService(mock(JdbcClient.class), mock(EmailVerificationGateway.class),false,"");
        assertThatThrownBy(() -> service.send("person@example.com","LOGIN","session","client"))
            .isInstanceOf(ServiceUnavailableException.class);
    }
}
