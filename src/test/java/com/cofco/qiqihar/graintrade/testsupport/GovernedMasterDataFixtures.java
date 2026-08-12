package com.cofco.qiqihar.graintrade.testsupport;

import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Test fixtures use the same append-only master-data governance entry point as production. */
public final class GovernedMasterDataFixtures {
    private GovernedMasterDataFixtures() {}

    public static void insertRegion(
            JdbcClient jdbc,
            String code,
            String name,
            String parentCode,
            String administrativeLevel,
            int sortOrder) {
        boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM platform.region WHERE code=:code)")
                .param("code", code).query(Boolean.class).single();
        if (exists) return;
        jdbc.sql("""
                SELECT platform.govern_master_data_change(
                  'REGION',:code,'INSERT',jsonb_build_object(
                    'code',:code,'name',:name,'parent_code',:parentCode,
                    'administrative_level',:level,'sort_order',:sortOrder),
                  clock_timestamp(),'production-tester','market-tester',
                  '自动化测试双人复核地区主数据夹具')
                """).param("code", code).param("name", name).param("parentCode", parentCode)
                .param("level", administrativeLevel).param("sortOrder", sortOrder)
                .query(Long.class).single();
    }

    public static void deleteRegions(JdbcClient jdbc, Collection<String> codes) {
        if (codes.isEmpty()) return;
        List<String> governedCodes = jdbc.sql("""
                SELECT code FROM platform.region WHERE code IN (:codes)
                ORDER BY CASE administrative_level
                  WHEN 'VILLAGE' THEN 4 WHEN 'TOWNSHIP' THEN 3
                  WHEN 'COUNTY' THEN 2 ELSE 1 END DESC,code
                """).param("codes", codes).query(String.class).list();
        governedCodes.forEach(code -> jdbc.sql("""
                SELECT platform.govern_master_data_change(
                  'REGION',region.code,'DELETE',to_jsonb(region),clock_timestamp(),
                  'production-tester','market-tester','自动化测试双人复核地区主数据清理')
                FROM platform.region region WHERE region.code=:code
                """).param("code", code).query(Long.class).single());
    }
}
