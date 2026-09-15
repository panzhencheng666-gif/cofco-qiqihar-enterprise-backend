package com.cofco.qiqihar.graintrade.shared.application;

import java.util.Map;

/** Read-only field/coordinate preflight. Identity linking and concurrency are checked again on save. */
public record BusinessValidationPreview(String id,long version,boolean fieldValidationPassed,
        String validationScope,String code,String message,Map<String,Object> details) {
    public static BusinessValidationPreview check(String id,long version,Runnable validation) {
        try {
            validation.run();
            return new BusinessValidationPreview(id,version,true,"FIELDS_AND_COORDINATES",null,
                    "字段与坐标校验通过；保存时仍会校验样本关联、重复期间和记录版本。",Map.of());
        } catch (ClientRequestException exception) {
            return new BusinessValidationPreview(id,version,false,"FIELDS_AND_COORDINATES",exception.code(),
                    exception.clientMessage(),exception.details());
        }
    }
}
