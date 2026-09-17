package com.cofco.qiqihar.graintrade.annotation.interfaceadapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.cofco.qiqihar.graintrade.annotation.application.UserMapAnnotationService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserMapAnnotationControllerTest {
    @Test void accountWithoutAnnotationReturnsSuccessfulEmptyData() throws Exception {
        var service=mock(UserMapAnnotationService.class);
        var authentication=mock(Authentication.class);
        when(authentication.getName()).thenReturn("account-a");
        when(service.current("account-a")).thenReturn(Optional.empty());
        var mvc=MockMvcBuilders.standaloneSetup(new UserMapAnnotationController(service)).build();
        mvc.perform(get("/api/v1/overview/map-annotation").principal(authentication))
            .andExpect(status().isOk()).andExpect(content().json("{\"data\":null}"));
        verify(service).current("account-a");
    }
}
