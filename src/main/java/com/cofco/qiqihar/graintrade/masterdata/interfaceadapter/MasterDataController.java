package com.cofco.qiqihar.graintrade.masterdata.interfaceadapter;

import com.cofco.qiqihar.graintrade.masterdata.application.MasterDataQuery;
import com.cofco.qiqihar.graintrade.masterdata.domain.BusinessPeriod;
import com.cofco.qiqihar.graintrade.masterdata.domain.Cultivar;
import com.cofco.qiqihar.graintrade.masterdata.domain.FieldDefinition;
import com.cofco.qiqihar.graintrade.masterdata.domain.ObjectType;
import com.cofco.qiqihar.graintrade.masterdata.domain.PageDefaultContext;
import com.cofco.qiqihar.graintrade.masterdata.domain.PageDefinition;
import com.cofco.qiqihar.graintrade.masterdata.domain.Product;
import com.cofco.qiqihar.graintrade.masterdata.domain.Region;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/master-data")
public class MasterDataController {

    private final MasterDataQuery query;

    public MasterDataController(MasterDataQuery query) {
        this.query = query;
    }

    @GetMapping("/regions")
    ApiResponse<List<RegionResponse>> regions() {
        return new ApiResponse<>(query.regions().stream().map(RegionResponse::from).toList());
    }

    @GetMapping("/products")
    ApiResponse<List<NamedResponse>> products() {
        return new ApiResponse<>(query.products().stream().map(NamedResponse::from).toList());
    }

    @GetMapping("/products/{productCode}/cultivars")
    ApiResponse<List<CultivarResponse>> cultivars(@PathVariable String productCode) {
        return new ApiResponse<>(query.cultivars(productCode).stream().map(CultivarResponse::from).toList());
    }

    @GetMapping("/object-types")
    ApiResponse<List<ObjectTypeResponse>> objectTypes(
            @RequestParam String productCode,
            @RequestParam String domain) {
        return new ApiResponse<>(query.objectTypes(productCode, domain).stream()
                .map(ObjectTypeResponse::from)
                .toList());
    }

    @GetMapping("/business-periods")
    ApiResponse<List<BusinessPeriodResponse>> businessPeriods() {
        return new ApiResponse<>(query.businessPeriods().stream().map(BusinessPeriodResponse::from).toList());
    }

    @GetMapping("/page-definitions")
    ApiResponse<PageDefinitionResponse> pageDefinition(
            @RequestParam String productCode,
            @RequestParam String domain,
            @RequestParam String pageKind) {
        return new ApiResponse<>(PageDefinitionResponse.from(
                query.pageDefinition(productCode, domain, pageKind)));
    }

    public record NamedResponse(String code, String name) {
        static NamedResponse from(Product product) {
            return new NamedResponse(product.code(), product.name());
        }
    }

    public record RegionResponse(String code, String name, String parentCode, String level) {
        static RegionResponse from(Region region) {
            return new RegionResponse(region.code(), region.name(), region.parentCode(), region.level());
        }
    }

    public record CultivarResponse(String code, String name, String productCode) {
        static CultivarResponse from(Cultivar cultivar) {
            return new CultivarResponse(cultivar.code(), cultivar.name(), cultivar.productCode());
        }
    }

    public record ObjectTypeResponse(String code, String name, String domain) {
        static ObjectTypeResponse from(ObjectType objectType) {
            return new ObjectTypeResponse(objectType.code(), objectType.name(), objectType.domain());
        }
    }

    public record BusinessPeriodResponse(String code, String name, LocalDate startsOn, LocalDate endsOn) {
        static BusinessPeriodResponse from(BusinessPeriod period) {
            return new BusinessPeriodResponse(period.code(), period.name(), period.startsOn(), period.endsOn());
        }
    }

    public record FieldResponse(String code, String name, String valueType, int sortOrder) {
        static FieldResponse from(FieldDefinition field) {
            return new FieldResponse(field.code(), field.name(), field.valueType(), field.sortOrder());
        }
    }

    public record DefaultContextResponse(
            String productCode,
            String businessPeriodCode,
            String businessBatchCode) {
        static DefaultContextResponse from(PageDefaultContext context) {
            return context == null ? null : new DefaultContextResponse(
                    context.productCode(), context.businessPeriodCode(), context.businessBatchCode());
        }
    }

    public record PageDefinitionResponse(
            String productCode,
            String domain,
            String pageKind,
            List<FieldResponse> fields,
            DefaultContextResponse defaultContext) {
        static PageDefinitionResponse from(PageDefinition definition) {
            return new PageDefinitionResponse(
                    definition.productCode(),
                    definition.domain(),
                    definition.pageKind(),
                    definition.fields().stream().map(FieldResponse::from).toList(),
                    DefaultContextResponse.from(definition.defaultContext()));
        }
    }
}
