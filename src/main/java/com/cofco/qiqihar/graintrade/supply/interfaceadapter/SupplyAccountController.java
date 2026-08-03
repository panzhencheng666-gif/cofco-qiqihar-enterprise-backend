package com.cofco.qiqihar.graintrade.supply.interfaceadapter;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;import com.cofco.qiqihar.graintrade.shared.interfaceadapter.ApiResponse;import com.cofco.qiqihar.graintrade.shared.interfaceadapter.StrictQueryParameters;
import com.cofco.qiqihar.graintrade.supply.application.*;import java.math.BigDecimal;import java.util.List;import java.util.Set;import java.util.regex.Pattern;
import org.springframework.util.MultiValueMap;import org.springframework.web.bind.annotation.*;
@RestController public class SupplyAccountController{
 private static final Pattern DECIMAL=Pattern.compile("^-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)$");private final SupplyAccountService service;public SupplyAccountController(SupplyAccountService service){this.service=service;}
 @GetMapping("/api/v1/supply-accounts") ApiResponse<List<SupplyAccountView>> list(@RequestParam MultiValueMap<String,String> params){StrictQueryParameters p=StrictQueryParameters.parse(params,Set.of("productCode","regionCode","marketingYear","resultState","version")::contains,SupplyAccountController::invalid);Integer version=p.optional("version")==null?null:integer(p.optional("version"));return new ApiResponse<>(service.list(p.required("productCode"),p.required("regionCode"),p.required("marketingYear"),p.optional("resultState"),version));}
 @PostMapping("/api/v1/supply-accounts/runs") ApiResponse<SupplyAccountView> run(@RequestBody RunRequest r){return new ApiResponse<>(service.run(r.command()));}
 record RunRequest(String productCode,String regionCode,String marketingYear,String approvedAdjustment,String adoptionReason,String adjustmentReason,Long expectedDecisionVersion,Boolean publish){SupplyRunCommand command(){if(expectedDecisionVersion==null||expectedDecisionVersion<0||publish==null||approvedAdjustment==null||!DECIMAL.matcher(approvedAdjustment).matches())throw invalid();return new SupplyRunCommand(productCode,regionCode,marketingYear,new BigDecimal(approvedAdjustment),adoptionReason,adjustmentReason,expectedDecisionVersion,publish);}}
 private static int integer(String v){try{return Integer.parseInt(v);}catch(NumberFormatException e){throw invalid();}}
 private static ClientRequestException invalid(){return new ClientRequestException("INVALID_SUPPLY_ACCOUNT_REQUEST","Supply account request is invalid");}
}
