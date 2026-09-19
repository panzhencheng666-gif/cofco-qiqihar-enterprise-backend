package com.cofco.qiqihar.graintrade.overview.interfaceadapter;

import com.cofco.qiqihar.graintrade.overview.application.OperationalSituationCatalogue;
import com.cofco.qiqihar.graintrade.overview.application.OperationalSituationService;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.StrictQueryParameters;
import java.util.Set;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationalSituationController {
    private final OperationalSituationService service;

    public OperationalSituationController(OperationalSituationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/overview/operational-situation")
    ApiResponse<OperationalSituationCatalogue> current(
            @RequestParam MultiValueMap<String, String> parameters) {
        var parsed = StrictQueryParameters.parse(parameters, Set.of("regionCode")::contains,
                OperationalSituationController::invalid);
        var regionCode = parsed.optional("regionCode");
        if (regionCode != null && !regionCode.matches("[0-9]{6,12}")) throw invalid();
        return new ApiResponse<>(service.current(regionCode));
    }

    private static ClientRequestException invalid() {
        return new ClientRequestException(
                "INVALID_OPERATIONAL_SITUATION_QUERY", "公开态势查询条件无效");
    }
}
