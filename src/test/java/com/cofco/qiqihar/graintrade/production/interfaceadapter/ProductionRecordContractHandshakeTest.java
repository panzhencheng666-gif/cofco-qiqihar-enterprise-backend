package com.cofco.qiqihar.graintrade.production.interfaceadapter;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cofco.qiqihar.graintrade.production.application.ProductionFactGroup;
import com.cofco.qiqihar.graintrade.production.application.ProductionRecordService;
import com.cofco.qiqihar.graintrade.production.application.ProductionFormDefinition;
import com.cofco.qiqihar.graintrade.production.application.ProductionSurveyFieldContract;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProductionRecordContractHandshakeTest {
    private static final String PRODUCT = "CORN";
    private static final String OBJECT_TYPE = "FARMER";

    private final ProductionRecordService service = mock(ProductionRecordService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ProductionRecordController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @BeforeEach
    void stubDefinition() {
        when(service.factDefinition(PRODUCT, OBJECT_TYPE)).thenReturn(new ProductionFormDefinition(
                PRODUCT,
                OBJECT_TYPE,
                ProductionSurveyFieldContract.VERSION,
                ProductionSurveyFieldContract.DIGEST,
                List.of(),
                List.of(new ProductionFactGroup("DETAIL", "明细", 10, List.of()))));
        when(service.factDefinition("UNKNOWN", OBJECT_TYPE))
                .thenThrow(new ClientRequestException("INVALID_PRODUCTION_RECORD", "Invalid production definition context"));
    }

    @Test
    void acceptsCorrectHandshakeAndLegacyReadWithBothExpectationsOmitted() throws Exception {
        mockMvc.perform(get("/api/v1/production-record-definitions")
                        .queryParam("productCode", PRODUCT)
                        .queryParam("objectTypeCode", OBJECT_TYPE)
                        .queryParam("contractVersion", ProductionSurveyFieldContract.VERSION)
                        .queryParam("contractDigest", ProductionSurveyFieldContract.DIGEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractVersion").value(ProductionSurveyFieldContract.VERSION))
                .andExpect(jsonPath("$.data.contractDigest").value(ProductionSurveyFieldContract.DIGEST));

        mockMvc.perform(get("/api/v1/production-record-definitions")
                        .queryParam("productCode", PRODUCT)
                        .queryParam("objectTypeCode", OBJECT_TYPE))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsWrongVersionDigestAndPartialHandshakeAsContractConflicts() throws Exception {
        mockMvc.perform(definitionRequest("obsolete", ProductionSurveyFieldContract.DIGEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONTRACT_MISMATCH"));
        mockMvc.perform(definitionRequest(ProductionSurveyFieldContract.VERSION, "sha256:" + "0".repeat(64)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONTRACT_MISMATCH"));
        mockMvc.perform(get("/api/v1/production-record-definitions")
                        .queryParam("productCode", PRODUCT)
                        .queryParam("objectTypeCode", OBJECT_TYPE)
                        .queryParam("contractVersion", ProductionSurveyFieldContract.VERSION))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONTRACT_MISMATCH"));
    }

    @Test
    void preservesProductObjectValidationAsBadRequestBeforeContractHandshake() throws Exception {
        mockMvc.perform(get("/api/v1/production-record-definitions")
                        .queryParam("productCode", "UNKNOWN")
                        .queryParam("objectTypeCode", OBJECT_TYPE)
                        .queryParam("contractVersion", "obsolete")
                        .queryParam("contractDigest", "sha256:" + "0".repeat(64)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PRODUCTION_RECORD"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder definitionRequest(
            String version, String digest) {
        return get("/api/v1/production-record-definitions")
                .queryParam("productCode", PRODUCT)
                .queryParam("objectTypeCode", OBJECT_TYPE)
                .queryParam("contractVersion", version)
                .queryParam("contractDigest", digest);
    }
}
