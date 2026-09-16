package com.cofco.qiqihar.graintrade.designsample.allocation;

import java.time.Instant;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class DesignSampleAllocationService {
    private final DesignSampleAllocationPlanner planner;private final DesignSampleTownshipWriter writer;
    private final JdbcClient jdbc;private final DataSource dataSource;
    public DesignSampleAllocationService(DesignSampleAllocationPlanner planner,DesignSampleTownshipWriter writer,
            JdbcClient jdbc,DataSource dataSource){this.planner=planner;this.writer=writer;this.jdbc=jdbc;this.dataSource=dataSource;}

    public DesignSampleAllocationPreflight preflight(){
        var townships=projectBatch(planner.planAll(),Instant.now());
        long villages=townships.stream().mapToLong(DesignSampleTownshipPlan::villageCount).sum();
        long active=jdbc.sql("SELECT count(*) FROM platform.design_sample_point WHERE lifecycle_status='ACTIVE'")
                .query(Long.class).single();
        return new DesignSampleAllocationPreflight(townships.size(),villages,active,List.of(),townships);
    }

    public ApplyBatch apply(String actor){
        try(Connection connection=dataSource.getConnection()) {
            boolean locked=false;
            try(PreparedStatement lock=connection.prepareStatement("SELECT pg_advisory_lock(209,1)")) {
                lock.execute();locked=true;
                return applyLocked(actor);
            } finally {
                if(locked)try(PreparedStatement unlock=connection.prepareStatement("SELECT pg_advisory_unlock(209,1)")) {
                    try(ResultSet result=unlock.executeQuery()) {
                        if(!result.next()||!result.getBoolean(1))throw new SQLException("设计样本批次锁释放失败");
                    }
                }
            }
        } catch(SQLException failure) {
            throw new IllegalStateException("无法取得设计样本批次锁",failure);
        }
    }

    private ApplyBatch applyLocked(String actor){
        UUID run=UUID.randomUUID();Instant candidateCutoff=Instant.now();
        var plans=projectBatch(planner.planAll(),candidateCutoff);
        List<DesignSampleTownshipWriter.ApplyResult> results=new ArrayList<>();
        Set<String> successfulTownships=new HashSet<>();int reused=0,created=0;
        for(var plan:plans){
            if(!plan.ready()){
                results.add(new DesignSampleTownshipWriter.ApplyResult(plan.townshipCode(),0,0,0,plan.blockers()));
                continue;
            }
            try {
                var written=writer.apply(plan.townshipCode(),actor,run,candidateCutoff);
                reused+=written.moved();created+=written.created();
                if(written.blockers().isEmpty())successfulTownships.add(plan.townshipCode());
                results.add(new DesignSampleTownshipWriter.ApplyResult(plan.townshipCode(),written.created(),
                        written.moved(),0,written.blockers()));
            } catch(RuntimeException failure) {
                results.add(new DesignSampleTownshipWriter.ApplyResult(plan.townshipCode(),0,0,0,
                        List.of("写入失败，已回滚该乡镇："+failure.getClass().getSimpleName())));
            }
        }
        Map<String,Integer> expiredByTownship=expiredByTownship(run,plans,successfulTownships);
        List<DesignSampleTownshipWriter.ApplyResult> completed=results.stream().map(result->
                new DesignSampleTownshipWriter.ApplyResult(result.townshipCode(),result.created(),result.moved(),
                        expiredByTownship.getOrDefault(result.townshipCode(),0),result.blockers())).toList();
        int expired=expiredByTownship.values().stream().mapToInt(Integer::intValue).sum();
        return new ApplyBatch(run,reused,created,expired,completed);
    }

    private List<DesignSampleTownshipPlan> projectBatch(List<DesignSampleTownshipPlan> plans,Instant candidateCutoff){
        List<ActiveRow> rows=jdbc.sql("""
                SELECT point.design_sample_point_id,point.region_code,region.administrative_level,
                       region.parent_code,point.created_at
                FROM platform.design_sample_point point JOIN platform.region region ON region.code=point.region_code
                WHERE point.lifecycle_status='ACTIVE' AND point.created_at<=:cutoff
                ORDER BY point.created_at,point.design_sample_point_id
                """).param("cutoff",Timestamp.from(candidateCutoff))
                .query((r,n)->new ActiveRow(r.getObject(1,UUID.class),r.getString(2),r.getString(3),
                        r.getString(4),r.getTimestamp(5).toInstant())).list();
        Map<String,String> parents=regionParents();Set<UUID> claimed=new HashSet<>();
        List<PoolEntry> pool=new ArrayList<>();Map<String,Projection> projections=new HashMap<>();
        for(var plan:plans){
            if(!plan.ready())continue;
            Set<String> keptVillages=new HashSet<>();Set<UUID> keptIds=new HashSet<>();
            for(ActiveRow row:rows)if(!claimed.contains(row.id())&&plan.selectedVillageCodes().contains(row.region())
                    &&keptVillages.add(row.region()))keptIds.add(row.id());
            claimed.addAll(keptIds);
            Set<String> ancestorCodes=ancestors(plan.townshipCode(),parents);
            List<PoolEntry> added=new ArrayList<>();
            for(ActiveRow row:rows)if(!claimed.contains(row.id())
                    &&(plan.townshipCode().equals(row.region())
                      || ("VILLAGE".equals(row.level())&&plan.townshipCode().equals(row.parent()))
                      || ancestorCodes.contains(row.region()))){
                claimed.add(row.id());added.add(new PoolEntry(row,plan.townshipCode()));
            }
            pool.sort(Comparator.comparing((PoolEntry entry)->entry.row().createdAt())
                    .thenComparing(entry->entry.row().id()));
            pool.addAll(added);
            int missing=plan.selectedVillageCodes().size()-keptIds.size();int moved=Math.min(pool.size(),missing);
            if(moved>0)pool=new ArrayList<>(pool.subList(moved,pool.size()));
            projections.put(plan.townshipCode(),new Projection(moved,missing-moved));
        }
        Map<String,Integer> expired=new HashMap<>();
        pool.forEach(entry->expired.merge(entry.originTownship(),1,Integer::sum));
        List<DesignSampleTownshipPlan> projected=new ArrayList<>();
        for(var plan:plans){
            Projection value=projections.get(plan.townshipCode());
            projected.add(value==null?plan:new DesignSampleTownshipPlan(plan.townshipCode(),plan.townshipName(),
                    plan.villageCount(),plan.adjacencyEdgeCount(),plan.selectedVillageCodes(),plan.preservedExisting(),
                    value.moved(),value.created(),expired.getOrDefault(plan.townshipCode(),0),
                    plan.coverageProof(),plan.blockers()));
        }
        return List.copyOf(projected);
    }

    private Map<String,Integer> expiredByTownship(UUID run,List<DesignSampleTownshipPlan> plans,
            Set<String> successfulTownships){
        Map<String,String> parents=regionParents();List<String> ready=plans.stream().filter(DesignSampleTownshipPlan::ready)
                .map(DesignSampleTownshipPlan::townshipCode).filter(successfulTownships::contains).sorted().toList();
        Map<String,Integer> result=new HashMap<>();
        var rows=jdbc.sql("""
                SELECT point.region_code,region.administrative_level,region.parent_code
                FROM platform.design_sample_point point JOIN platform.region region ON region.code=point.region_code
                WHERE point.assignment_run_id=:run AND point.lifecycle_status='EXPIRED'
                ORDER BY point.created_at,point.design_sample_point_id
                """).param("run",run).query((r,n)->new RegionRow(r.getString(1),r.getString(2),r.getString(3))).list();
        for(RegionRow row:rows){
            String owner="TOWNSHIP".equals(row.level())?row.region():
                    "VILLAGE".equals(row.level())?row.parent():ready.stream()
                    .filter(township->ancestors(township,parents).contains(row.region())).findFirst().orElse(null);
            if(owner!=null)result.merge(owner,1,Integer::sum);
        }
        return result;
    }

    private Map<String,String> regionParents(){
        Map<String,String> parents=new HashMap<>();
        jdbc.sql("SELECT code,parent_code FROM platform.region").query((r,n)->Map.entry(r.getString(1),
                Optional.ofNullable(r.getString(2)).orElse(""))).list().forEach(entry->parents.put(entry.getKey(),entry.getValue()));
        return parents;
    }
    private static Set<String> ancestors(String code,Map<String,String> parents){
        Set<String> result=new HashSet<>();String current=parents.get(code);
        while(current!=null&&!current.isBlank()&&result.add(current))current=parents.get(current);
        return result;
    }

    private record ActiveRow(UUID id,String region,String level,String parent,Instant createdAt) {}
    private record RegionRow(String region,String level,String parent) {}
    private record PoolEntry(ActiveRow row,String originTownship) {}
    private record Projection(int moved,int created) {}
    public record ApplyBatch(UUID runId,int reused,int created,int expired,
            List<DesignSampleTownshipWriter.ApplyResult> townships) {}
}
