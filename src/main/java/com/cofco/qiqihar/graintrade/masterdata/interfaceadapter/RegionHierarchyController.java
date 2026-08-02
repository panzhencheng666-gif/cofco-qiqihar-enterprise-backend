package com.cofco.qiqihar.graintrade.masterdata.interfaceadapter;

import com.cofco.qiqihar.graintrade.masterdata.application.MasterDataQuery;
import com.cofco.qiqihar.graintrade.masterdata.domain.Region;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RegionHierarchyController {

    private final MasterDataQuery query;

    public RegionHierarchyController(MasterDataQuery query) {
        this.query = query;
    }

    @GetMapping("/api/v1/regions")
    ApiResponse<List<RegionNodeResponse>> children(
            @RequestParam(required = false) String parentCode) {
        return new ApiResponse<>(query.regionChildren(parentCode).stream()
                .map(RegionNodeResponse::from)
                .toList());
    }

    @GetMapping("/api/v1/regions/{regionCode}/path")
    ApiResponse<List<RegionNodeResponse>> path(@PathVariable String regionCode) {
        return new ApiResponse<>(query.regionPath(regionCode).stream()
                .map(RegionNodeResponse::from)
                .toList());
    }

    record RegionNodeResponse(String id, String label, String parentId, String level) {
        static RegionNodeResponse from(Region region) {
            return new RegionNodeResponse(
                    region.code(), region.name(), region.parentCode(), region.level());
        }
    }
}
