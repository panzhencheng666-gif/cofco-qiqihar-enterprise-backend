package com.cofco.qiqihar.riskintelligence.web;

import com.cofco.qiqihar.graintrade.risk.interfaceadapter.RiskModelController;

import com.cofco.qiqihar.graintrade.risk.application.*;
import com.cofco.qiqihar.riskintelligence.security.*;
import com.cofco.qiqihar.riskintelligence.web.RiskApiErrorHandler;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RiskModelAuthorizationTest {
    private final RiskTrainingRepository repository = mock(RiskTrainingRepository.class);
    private final RiskModelOperationsService service = new RiskModelOperationsService(repository, Clock.systemUTC());

    @Test void regionalUpdaterCannotEnqueueGlobalTrainingOrReadGlobalOverview() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new RiskModelController(service))
                .setControllerAdvice(new RiskApiErrorHandler()).build();
        var session = new RiskBusinessSession("regional", Set.of("BUSINESS_READ", "BUSINESS_UPDATE"), false, Set.of("230221"));
        mvc.perform(post("/api/v1/risk/models/{id}/training-requests", UUID.randomUUID())
                .requestAttr(RiskBusinessSession.REQUEST_ATTRIBUTE, session)
                .header("X-Actor", "root").header("X-Root-Administrator", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/risk/models/overview")
                .requestAttr(RiskBusinessSession.REQUEST_ATTRIBUTE, session))
                .andExpect(status().isForbidden());
        verifyNoInteractions(repository);
    }

    @Test void trustedRootCanEnqueueWithAuthoritativeActor() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new RiskModelController(service))
                .setControllerAdvice(new RiskApiErrorHandler()).build();
        UUID model = UUID.randomUUID();
        var root = new RiskBusinessSession("trusted-root", Set.of(), true, Set.of());
        mvc.perform(post("/api/v1/risk/models/{id}/training-requests", model)
                .requestAttr(RiskBusinessSession.REQUEST_ATTRIBUTE, root).header("X-Actor", "forged"))
                .andExpect(status().isOk());
        verify(repository).enqueueManualExecution(eq(model), eq("trusted-root"), any());
    }
}
