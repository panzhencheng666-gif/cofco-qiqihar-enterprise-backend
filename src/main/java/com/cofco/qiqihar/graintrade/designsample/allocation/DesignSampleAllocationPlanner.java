package com.cofco.qiqihar.graintrade.designsample.allocation;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

@Service
public class DesignSampleAllocationPlanner {
    private final JdbcClient jdbc;private final ExactVillageCoverageSolver solver=new ExactVillageCoverageSolver();
    private final long maxSearchNodes;
    public DesignSampleAllocationPlanner(JdbcClient jdbc,
            @Value("${QIQIHAR_DESIGN_ALLOCATION_MAX_SEARCH_NODES:5000000}") long maxSearchNodes){
        this.jdbc=jdbc;this.maxSearchNodes=maxSearchNodes;
    }

    @Transactional(readOnly=true)
    public List<DesignSampleTownshipPlan> planAll() {
        return jdbc.sql("""
                SELECT town.code,town.name FROM platform.region town
                WHERE town.administrative_level='TOWNSHIP'
                  AND EXISTS(SELECT 1 FROM platform.region village
                             WHERE village.parent_code=town.code AND village.administrative_level='VILLAGE')
                ORDER BY town.code
                """).query((r,n)->Map.entry(r.getString(1),r.getString(2))).list().stream()
                .map(entry->planTownship(entry.getKey(),entry.getValue())).toList();
    }

    @Transactional(readOnly=true)
    public DesignSampleTownshipPlan planTownship(String townshipCode,String townshipName) {
        var villages=jdbc.sql("""
                SELECT village.code FROM platform.region village
                WHERE village.parent_code=:township AND village.administrative_level='VILLAGE'
                ORDER BY village.code
                """).param("township",townshipCode).query(String.class).list();
        List<String> blockers=new ArrayList<>();
        if(villages.size()<3)blockers.add("乡镇行政村少于3个，无法满足每村最多一个且乡镇至少3个样本点");
        var invalid=jdbc.sql("""
                SELECT village.code FROM platform.region village
                LEFT JOIN overview.administrative_boundary boundary ON boundary.region_code=village.code
                LEFT JOIN overview.administrative_boundary_render render ON render.region_code=village.code
                WHERE village.parent_code=:township AND village.administrative_level='VILLAGE'
                  AND (boundary.region_code IS NULL OR NOT ST_IsValid(boundary.geometry) OR ST_IsEmpty(boundary.geometry)
                    OR render.region_code IS NULL OR NOT ST_IsValid(render.geometry) OR ST_IsEmpty(render.geometry))
                ORDER BY village.code
                """).param("township",townshipCode).query(String.class).list();
        if(!invalid.isEmpty())blockers.add("缺少有效行政村边界："+String.join(",",invalid));
        if(!blockers.isEmpty())return blocked(townshipCode,townshipName,villages.size(),blockers);
        SortedMap<String,SortedSet<String>> graph=new TreeMap<>();villages.forEach(code->graph.put(code,new TreeSet<>()));
        jdbc.sql("""
                SELECT left_region.code,right_region.code
                FROM platform.region left_region JOIN platform.region right_region
                  ON left_region.parent_code=right_region.parent_code AND left_region.code<right_region.code
                JOIN overview.administrative_boundary left_boundary ON left_boundary.region_code=left_region.code
                JOIN overview.administrative_boundary right_boundary ON right_boundary.region_code=right_region.code
                WHERE left_region.parent_code=:township
                  AND left_region.administrative_level='VILLAGE' AND right_region.administrative_level='VILLAGE'
                  AND ST_Touches(left_boundary.geometry,right_boundary.geometry)
                ORDER BY left_region.code,right_region.code
                """).param("township",townshipCode).query((r,n)->Map.entry(r.getString(1),r.getString(2))).list()
                .forEach(edge->{graph.get(edge.getKey()).add(edge.getValue());graph.get(edge.getValue()).add(edge.getKey());});
        List<String> activeRegions=jdbc.sql("""
                SELECT point.region_code FROM platform.design_sample_point point
                JOIN platform.region region ON region.code=point.region_code
                WHERE point.lifecycle_status='ACTIVE'
                  AND (point.region_code=:township
                    OR (region.parent_code=:township AND region.administrative_level='VILLAGE'))
                ORDER BY point.created_at,point.design_sample_point_id
                """).param("township",townshipCode).query(String.class).list();
        Set<String> existing=new HashSet<>(activeRegions);
        ExactVillageCoverageSolver.Result result;
        try {result=solver.solve(graph,existing,maxSearchNodes);}
        catch(ExactVillageCoverageSolver.SearchLimitExceededException timeout) {
            return blocked(townshipCode,townshipName,villages.size(),List.of("精确覆盖求解超过资源上限，未生成写入方案"));
        }
        Map<String,Long> counts=activeRegions.stream().collect(java.util.stream.Collectors.groupingBy(
                java.util.function.Function.identity(),TreeMap::new,java.util.stream.Collectors.counting()));
        int kept=(int)result.selected().stream().filter(code->counts.getOrDefault(code,0L)>0).count();
        int missing=result.selected().size()-kept;
        int reusable=activeRegions.size()-kept;
        int moved=Math.min(missing,reusable);
        SortedMap<String,String> proof=new TreeMap<>();
        for(String village:villages) {
            String sample=result.selected().contains(village)?village:graph.get(village).stream()
                    .filter(result.selected()::contains).findFirst().orElseThrow();
            proof.put(village,sample);
        }
        int edges=graph.values().stream().mapToInt(Set::size).sum()/2;
        return new DesignSampleTownshipPlan(townshipCode,townshipName,villages.size(),edges,result.selected(),
                result.preservedExisting(),moved,missing-moved,reusable-moved,
                Collections.unmodifiableSortedMap(proof),List.of());
    }

    private static DesignSampleTownshipPlan blocked(String code,String name,int villageCount,List<String> blockers) {
        return new DesignSampleTownshipPlan(code,name,villageCount,0,
                Collections.unmodifiableSortedSet(new TreeSet<>()),0,0,0,0,
                Collections.unmodifiableSortedMap(new TreeMap<>()),List.copyOf(blockers));
    }
}
