package com.cofco.qiqihar.graintrade.shared.infrastructure;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Runs inside the save transaction after identity linking, before publishing a saved event. */
public final class FormalBusinessSaveGuard {
    private FormalBusinessSaveGuard() {}

    public static void verify(JdbcClient jdbc, String domain, String id) {
        String table, idColumn, object, prefix;
        switch(domain) {
            case "MARKET" -> { table="market.market_record"; idColumn="record_id"; object="object_type_code"; prefix="MKT_"; }
            case "PRODUCTION" -> { table="production.production_record"; idColumn="record_id"; object="object_type_code"; prefix="PROD_"; }
            case "LOGISTICS" -> { table="logistics.route_event"; idColumn="event_id"; object="'ROUTE_EVENT'"; prefix="LOG_"; }
            default -> throw new IllegalArgumentException("Unsupported business domain");
        }
        Fact fact=jdbc.sql("SELECT sample_point_id,product_code,"+object+" object_type,survey_year,survey_month FROM "+table+" WHERE "+idColumn+"::text=:id")
            .param("id",id).query((row,index)->new Fact(row.getObject("sample_point_id",UUID.class),row.getString("product_code"),
                row.getString("object_type"),row.getInt("survey_year"),row.getObject("survey_month",Integer.class))).single();
        if(fact.point()==null)throw ClientRequestException.field("FORMAL_SAMPLE_IDENTITY_REQUIRED",prefix+"SAMPLE_NAME",
                "样本信息不完整，无法建立真实样本点，请填写样本名称、联系方式和坐标后保存。");
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
            .param("key","FORMAL_FACT|"+domain+"|"+fact.point()+"|"+fact.product()+"|"+fact.object()+"|"+fact.year()+"|"+fact.month())
            .query((row,index)->true).single();
        long count=jdbc.sql("SELECT count(*) FROM "+table+" WHERE sample_point_id=:point AND product_code=:product AND "+object+
                "=:object AND survey_year=:year AND survey_month IS NOT DISTINCT FROM :month AND status_code<>'VOIDED' AND "+idColumn+"::text<>:id")
            .param("point",fact.point()).param("product",fact.product()).param("object",fact.object())
            .param("year",fact.year()).param("month",fact.month(),java.sql.Types.INTEGER).param("id",id).query(Long.class).single();
        if(count>0)throw ClientRequestException.field("SAMPLE_PERIOD_RECORD_CONFLICT",prefix+"SAMPLE_NAME",
                "相同样本、产品和调查期间已有记录，请修改已有记录，不要重复新建。");
    }
    private record Fact(UUID point,String product,String object,int year,Integer month) {}
}
