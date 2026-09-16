package com.cofco.qiqihar.graintrade.identity.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.cofco.qiqihar.graintrade.identity.application.EmailChallengeService;
import com.cofco.qiqihar.graintrade.identity.application.EmployeeRegistrationService;
import com.cofco.qiqihar.graintrade.identity.application.RegistrationDraftService;
import com.cofco.qiqihar.graintrade.identity.application.SmsChallengeService;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RegistrationEntryControllerTest {
    private final EmployeeRegistrationService employees=mock(EmployeeRegistrationService.class);
    private final RegistrationDraftService drafts=mock(RegistrationDraftService.class);
    private final SmsChallengeService sms=mock(SmsChallengeService.class);
    private final EmailChallengeService email=mock(EmailChallengeService.class);
    private final RegistrationEntryController controller=new RegistrationEntryController(employees,drafts,sms,email);

    @Test
    void rejectsMissingOrUnsupportedVerificationMethodWithoutSendingAnyCode() {
        MockHttpServletRequest request=new MockHttpServletRequest();
        request.getSession();

        assertThatThrownBy(() -> controller.challenge(
                new RegistrationEntryController.Verification(null,"13800000000","person@example.com"),request))
                .isInstanceOf(ClientRequestException.class);
        assertThatThrownBy(() -> controller.challenge(
                new RegistrationEntryController.Verification("PASSWORD","13800000000","person@example.com"),request))
                .isInstanceOf(ClientRequestException.class);

        verify(sms,never()).send(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        verify(email,never()).send(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }
}
