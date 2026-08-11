package com.cofco.qiqihar.graintrade.logistics.interfaceadapter;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsDraft;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsDefinitionView;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsRecordView;
import com.cofco.qiqihar.graintrade.logistics.application.LogisticsService;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.BoundedInput;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;
import com.cofco.qiqihar.graintrade.shared.interfaceadapter.StrictQueryParameters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class LogisticsController {
 private static final Pattern FILTER=Pattern.compile("^filter\\.([A-Za-z0-9_-]+)$");
 private static final Set<String> CORE=Set.of("productCode","pageNumber","pageSize");
 private final LogisticsService service; public LogisticsController(LogisticsService service){this.service=service;}
 @GetMapping("/api/v1/logistics-records") ApiResponse<PageResponse> list(@RequestParam MultiValueMap<String,String> parameters){
  StrictQueryParameters p=StrictQueryParameters.parse(parameters,n->CORE.contains(n)||FILTER.matcher(n).matches(),LogisticsController::invalid);
  Map<String,String> filters=new LinkedHashMap<>();p.values().forEach((k,v)->{Matcher m=FILTER.matcher(k);if(m.matches())filters.put(m.group(1),v);});
  PagedResult<LogisticsRecordView> page=service.list(p.required("productCode"),p.integer("pageNumber",0),p.integer("pageSize",-1),filters);return new ApiResponse<>(PageResponse.from(page));
 }
 @GetMapping("/api/v1/logistics-records/{id}") ApiResponse<RecordResponse> detail(@PathVariable String id){return new ApiResponse<>(RecordResponse.from(service.detail(id)));}
 @GetMapping("/api/v1/logistics-record-definitions") ApiResponse<LogisticsDefinitionView> definition(@RequestParam String productCode){return new ApiResponse<>(service.definition(productCode));}
 @PostMapping("/api/v1/logistics-records") @ResponseStatus(HttpStatus.CREATED) ApiResponse<RecordResponse> create(@RequestBody DraftRequest request){return new ApiResponse<>(RecordResponse.from(service.create(request.toDraft())));}
 @PutMapping("/api/v1/logistics-records/{id}") ApiResponse<RecordResponse> save(@PathVariable String id,@RequestBody DraftRequest request){return new ApiResponse<>(RecordResponse.from(service.save(id,request.requiredVersion(),request.toDraft())));}
 @PostMapping("/api/v1/logistics-records/{id}/submit") ApiResponse<RecordResponse> submit(@PathVariable String id,@RequestBody VersionRequest r){return new ApiResponse<>(RecordResponse.from(service.submit(id,r.requiredVersion())));}
 @PostMapping("/api/v1/logistics-records/{id}/approve") ApiResponse<RecordResponse> approve(@PathVariable String id,@RequestBody VersionRequest r){return new ApiResponse<>(RecordResponse.from(service.approve(id,r.requiredVersion())));}
 @PostMapping("/api/v1/logistics-records/{id}/return") ApiResponse<RecordResponse> returned(@PathVariable String id,@RequestBody ReturnRequest r){return new ApiResponse<>(RecordResponse.from(service.returned(id,r.requiredVersion(),r.validatedReason())));}
 @PostMapping("/api/v1/logistics-records/{id}/void") ApiResponse<RecordResponse> voidRecord(@PathVariable String id,@RequestBody VersionRequest r){return new ApiResponse<>(RecordResponse.from(service.voidRecord(id,r.requiredVersion())));}
 record DraftRequest(String productCode,Map<String,String> values,Long version){
  LogisticsDraft toDraft(){return new LogisticsDraft(productCode,values);}
  long requiredVersion(){if(version==null||version<0)throw invalid();return version;}
 }
 record VersionRequest(Long version){long requiredVersion(){if(version==null||version<0)throw invalid();return version;}}
 record ReturnRequest(Long version,String reason){long requiredVersion(){if(version==null||version<0)throw invalid();return version;}String validatedReason(){BoundedInput.requireText("INVALID_LOGISTICS_RECORD",reason);return reason;}}
 record RecordResponse(String id,String productCode,Map<String,String> values,Map<String,String> displayValues,String status,String returnReason,List<String> allowedActions,long version){static RecordResponse from(LogisticsRecordView v){return new RecordResponse(v.id(),v.productCode(),v.values(),v.displayValues(),v.status().name(),v.returnReason(),v.allowedActions(),v.version());}}
 record PageResponse(List<RecordResponse> items,int pageNumber,int pageSize,long totalElements,int totalPages){static PageResponse from(PagedResult<LogisticsRecordView> p){return new PageResponse(p.items().stream().map(RecordResponse::from).toList(),p.pageNumber(),p.pageSize(),p.totalElements(),p.totalPages());}}
 private static ClientRequestException invalid(){return new ClientRequestException("INVALID_LOGISTICS_RECORD","Logistics record or query is invalid");}
}
