package com.cofco.qiqihar.graintrade.shared.security.interfaceadapter;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.cofco.qiqihar.graintrade.risk.application.RiskModelOperationsService;
import com.cofco.qiqihar.graintrade.risk.application.RiskWorkbenchService;
import com.cofco.qiqihar.graintrade.risk.interfaceadapter.RiskModelController;
import com.cofco.qiqihar.graintrade.risk.interfaceadapter.RiskWorkbenchController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.util.Map;
import java.util.UUID;

class RiskApiAvailabilityInterceptorTest {
    private static final String ID="00000000-0000-0000-0000-000000000101";
    @Configuration(proxyBeanMethods=false) @EnableWebMvc
    static class TestConfiguration {
        @Bean RiskModelOperationsService models(){return mock(RiskModelOperationsService.class);}
        @Bean RiskWorkbenchService workbench(){return mock(RiskWorkbenchService.class);}
        @Bean RiskModelController modelController(RiskModelOperationsService s){return new RiskModelController(s);}
        @Bean RiskWorkbenchController workbenchController(RiskWorkbenchService s){return new RiskWorkbenchController(s);}
        @Bean OtherController other(){return new OtherController();}
    }
    @RestController static class OtherController {
        @GetMapping("/api/v1/overview/gate-probe") String read(){return "unchanged";}
    }
    private AnnotationConfigWebApplicationContext context(Map<String,Object> properties) {
        var c=new AnnotationConfigWebApplicationContext();c.setServletContext(new MockServletContext());
        c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",properties));
        c.register(TestConfiguration.class);
        c.register(RiskApiAvailabilityInterceptor.class);
        c.refresh();
        var models=c.getBean(RiskModelOperationsService.class);
        when(models.overview()).thenReturn(new com.cofco.qiqihar.graintrade.risk.application.RiskModelOverview(
                java.util.List.of(),java.util.List.of(),java.util.List.of(),java.time.Instant.EPOCH));
        when(models.requestTraining(any())).thenReturn(new com.cofco.qiqihar.graintrade.risk.application.RiskTrainingRequest(
                UUID.fromString(ID),UUID.fromString(ID),"QUEUED",java.time.Instant.EPOCH));
        return c;
    }
    private void rejectsRiskRequests(Map<String,Object> properties) throws Exception {
        try(var c=context(properties)) {
            MockMvc mvc=MockMvcBuilders.webAppContextSetup(c).build();
            for(String path:new String[]{"/api/v1/risk/models/overview","/api/v1/risk/workbench/assessments","/api/v1/risk/workbench/assessments/"+ID})
                mvc.perform(get(path)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RISK_API_DISABLED"));
            mvc.perform(post("/api/v1/risk/models/"+ID+"/training-requests")).andExpect(status().isServiceUnavailable());
            mvc.perform(post("/api/v1/risk/workbench/assessments/"+ID+"/feedback").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable());
            verifyNoInteractions(c.getBean(RiskModelOperationsService.class),c.getBean(RiskWorkbenchService.class));
        }
    }
    @Test void missingSettingDisablesAllRiskHandlers() throws Exception {rejectsRiskRequests(Map.of());}
    @Test void explicitFalseDisablesAllRiskHandlers() throws Exception {rejectsRiskRequests(Map.of("qiqihar.risk.api.enabled","false"));}
    @Test void trainingFlagCannotEnableManualRoutes() throws Exception {rejectsRiskRequests(Map.of("qiqihar.risk.training.enabled","true"));}
    @Test void disabledRoutesRespectServletContextPath() throws Exception {
        try(var c=context(Map.of())) {
            MockMvcBuilders.webAppContextSetup(c).build().perform(get("/terminal/api/v1/risk/models/overview").contextPath("/terminal"))
                .andExpect(status().isServiceUnavailable());
            verifyNoInteractions(c.getBean(RiskModelOperationsService.class));
        }
    }
    @Test void unrelatedBusinessHandlerIsUnchanged() throws Exception {
        try(var c=context(Map.of())) {
            MockMvcBuilders.webAppContextSetup(c).build().perform(get("/api/v1/overview/gate-probe"))
                .andExpect(status().isOk()).andExpect(content().string("unchanged"));
        }
    }
    @Test void explicitTruePassesToExistingService() throws Exception {
        try(var c=context(Map.of("qiqihar.risk.api.enabled","true"))) {
            var mvc=MockMvcBuilders.webAppContextSetup(c).build();
            mvc.perform(get("/api/v1/risk/models/overview")).andExpect(status().isOk());
            mvc.perform(post("/api/v1/risk/models/"+ID+"/training-requests")).andExpect(status().isOk());
            verify(c.getBean(RiskModelOperationsService.class)).overview();
            verify(c.getBean(RiskModelOperationsService.class)).requestTraining(UUID.fromString(ID));
        }
    }
}
