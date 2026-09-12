package com.cofco.qiqihar.graintrade.formalsamplepoint.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import com.cofco.qiqihar.graintrade.shared.application.PagedResult;
import com.cofco.qiqihar.graintrade.shared.security.application.AccessControl;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HistoricalFormalSampleService {
    private final HistoricalFormalSampleRepository repository;
    private final AccessControl access;
    public HistoricalFormalSampleService(HistoricalFormalSampleRepository repository, AccessControl access) {
        this.repository=repository; this.access=access;
    }
    @Transactional(readOnly=true)
    public PagedResult<HistoricalFormalSample> list(String domain,String product,Integer year,String region,
            String keyword,int page,int size) {
        if(domain==null || !Set.of("PRODUCTION","MARKET","LOGISTICS").contains(domain)
                || product==null || !Set.of("CORN","RICE","SOYBEAN").contains(product)
                || (year!=null && (year<1900 || year>2200)) || page<0 || page>100000 || size<1 || size>100
                || (region!=null && !region.matches("[0-9]{6,12}")) || (keyword!=null && keyword.length()>200))
            throw new ClientRequestException("INVALID_HISTORICAL_SAMPLE_QUERY","历史样本查询参数不正确");
        var scope=access.requireBusinessReadScope();
        return repository.findPage(domain,product,year,region,keyword==null?null:keyword.strip(),page,size,scope.regionCodes());
    }
}
