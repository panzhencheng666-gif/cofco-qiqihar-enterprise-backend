package com.cofco.qiqihar.graintrade.designsample.allocation;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class DesignSampleAllocationService {
    private final DesignSampleAllocationPlanner planner;private final DesignSampleTownshipWriter writer;
    private final JdbcClient jdbc;
    public DesignSampleAllocationService(DesignSampleAllocationPlanner planner,DesignSampleTownshipWriter writer,
            JdbcClient jdbc){this.planner=planner;this.writer=writer;this.jdbc=jdbc;}
    public DesignSampleAllocationPreflight preflight(){
        var townships=planner.planAll();
        long villages=townships.stream().mapToLong(DesignSampleTownshipPlan::villageCount).sum();
        long active=jdbc.sql("SELECT count(*) FROM platform.design_sample_point WHERE lifecycle_status='ACTIVE'")
                .query(Long.class).single();
        long unsupported=jdbc.sql("""
                SELECT count(*) FROM platform.design_sample_point point
                JOIN platform.region region ON region.code=point.region_code
                WHERE point.lifecycle_status='ACTIVE'
                  AND region.administrative_level NOT IN ('TOWNSHIP','VILLAGE')
                """).query(Long.class).single();
        List<String> blockers=unsupported==0?List.of():List.of(
                "存在 "+unsupported+" 条市级或县级有效设计样本记录；当前批次不会忽略或覆盖，需先确认跨乡镇稳定复用规则");
        return new DesignSampleAllocationPreflight(townships.size(),villages,active,blockers,townships);
    }
    public ApplyBatch apply(String actor){UUID run=UUID.randomUUID();List<DesignSampleTownshipWriter.ApplyResult> results=new ArrayList<>();
        var preflight=preflight();
        if(!preflight.globalBlockers().isEmpty()) {
            for(var plan:preflight.townships())results.add(new DesignSampleTownshipWriter.ApplyResult(
                    plan.townshipCode(),0,0,0,preflight.globalBlockers()));
            return new ApplyBatch(run,List.copyOf(results));
        }
        for(var plan:preflight.townships())results.add(plan.ready()?writer.apply(plan.townshipCode(),actor,run):
                new DesignSampleTownshipWriter.ApplyResult(plan.townshipCode(),0,0,0,plan.blockers()));
        return new ApplyBatch(run,List.copyOf(results));}
    public record ApplyBatch(UUID runId,List<DesignSampleTownshipWriter.ApplyResult> townships) {}
}
