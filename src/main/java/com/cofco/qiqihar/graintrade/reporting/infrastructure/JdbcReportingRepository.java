package com.cofco.qiqihar.graintrade.reporting.infrastructure;

import com.cofco.qiqihar.graintrade.reporting.application.*;
import com.cofco.qiqihar.graintrade.reporting.domain.ReportExportContent;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.SqlParameterValue;
import java.sql.Types;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcReportingRepository implements ReportingRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    public JdbcReportingRepository(JdbcClient jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Override public ReportParameterOptionsView options() {
        Map<String, List<ReportDefinitionView.Section>> sections = new HashMap<>();
        jdbc.sql("""
                SELECT definition.code,section.section_code,section.title,section.sort_order
                FROM reporting.report_definition definition JOIN reporting.report_definition_section section
                ON section.report_definition_id=definition.report_definition_id ORDER BY definition.sort_order,section.sort_order""")
            .query((row,n) -> Map.entry(row.getString(1), new ReportDefinitionView.Section(row.getString(2),row.getString(3),row.getInt(4))))
            .list().forEach(entry -> sections.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(entry.getValue()));
        List<ReportDefinitionView> definitions = jdbc.sql("""
                SELECT code,name,business_domain,business_subtype,frequency_code,version_no
                FROM reporting.report_definition WHERE active ORDER BY sort_order""").query((row,n) -> new ReportDefinitionView(
                row.getString(1),row.getString(2),row.getString(3),row.getString(4),row.getString(5),row.getInt(6),sections.getOrDefault(row.getString(1),List.of()))).list();
        return new ReportParameterOptionsView(definitions, options("SELECT code,name FROM platform.product ORDER BY sort_order"),
                options("SELECT code,name FROM platform.cultivar ORDER BY product_code,sort_order"),
                options("SELECT DISTINCT administrative_level,administrative_level FROM platform.region ORDER BY administrative_level"),
                options("SELECT code,name FROM platform.region ORDER BY sort_order"), options("SELECT code,name FROM platform.business_period ORDER BY sort_order DESC"),
                options("SELECT format_code,label FROM reporting.report_output_format WHERE enabled ORDER BY sort_order"));
    }

    @Override public ReportPreviewMaterial loadPreviewMaterial(ReportPreviewCommand command) {
        DefinitionRow definition = jdbc.sql("""
                SELECT report_definition_id AS id,code,name,business_domain,business_subtype,frequency_code,version_no
                FROM reporting.report_definition WHERE code=:code AND active""").param("code", command.definitionCode()).query((row, index) -> new DefinitionRow(
                        row.getLong("id"), row.getString("code"), row.getString("name"), row.getString("business_domain"),
                        row.getString("business_subtype"), row.getString("frequency_code"), row.getInt("version_no"))).optional().orElse(null);
        if (definition == null || !exists("SELECT 1 FROM platform.product WHERE code=:code",command.productCode())
                || !exists("SELECT 1 FROM platform.region WHERE code=:code AND administrative_level=:level",command.regionCode(),command.regionLevel())
                || !exists("SELECT 1 FROM platform.business_period WHERE code=:code",command.periodCode())) return null;
        long count = approvedCount(definition.businessDomain(), command);
        String summary = "{\"approvedRecordCount\":" + count + ",\"businessDomain\":\"" + definition.businessDomain() + "\"}";
        return new ReportPreviewMaterial(new ReportDefinitionView(definition.code(),definition.name(),definition.businessDomain(),definition.businessSubtype(),definition.frequencyCode(),definition.versionNo(), sections(definition.id())),
                label("SELECT name FROM platform.product WHERE code=:code",command.productCode()), label("SELECT name FROM platform.region WHERE code=:code",command.regionCode()),
                label("SELECT name FROM platform.business_period WHERE code=:code",command.periodCode()),summary,count);
    }

    @Override public ReportPreviewView persistPreview(ReportPreviewPersistence value) {
        String previewId=UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO reporting.approved_dataset(dataset_id,report_definition_id,product_code,cultivar_code,region_level,region_code,period_code,frequency_code,source_state,source_summary,immutable_digest,captured_at,captured_by)
                SELECT CAST(:dataset AS uuid),report_definition_id,:product,:cultivar,:level,:region,:period,frequency_code,'APPROVED',CAST(:summary AS jsonb),:digest,:now,:actor
                FROM reporting.report_definition WHERE code=:definition""")
            .param("dataset",value.datasetId()).param("product",value.command().productCode()).param("cultivar",value.command().cultivarCode()).param("level",value.command().regionLevel()).param("region",value.command().regionCode()).param("period",value.command().periodCode()).param("summary",value.material().approvedSummaryJson()).param("digest",value.datasetDigest()).param("now",Timestamp.from(value.now())).param("actor",value.actor()).param("definition",value.command().definitionCode()).update();
        jdbc.sql("""
                INSERT INTO reporting.report_preview(preview_id,report_definition_id,dataset_id,parameter_snapshot,content_snapshot,content_digest,created_by,created_at,expires_at)
                SELECT CAST(:preview AS uuid),report_definition_id,CAST(:dataset AS uuid),CAST(:parameters AS jsonb),CAST(:content AS jsonb),:digest,:actor,:now,:expires
                FROM reporting.report_definition WHERE code=:definition""")
            .param("preview",previewId).param("dataset",value.datasetId()).param("parameters",parameters(value.command())).param("content",value.contentJson()).param("digest",value.contentDigest()).param("actor",value.actor()).param("now",Timestamp.from(value.now())).param("expires",Timestamp.from(value.expiresAt())).param("definition",value.command().definitionCode()).update();
        audit("PREVIEW",previewId,"PREVIEWED",value.actor(),value.now(),"{}");
        return view(previewId,value.command().definitionCode(),value.datasetId(),value.contentJson(),value.expiresAt(),0);
    }

    @Override public ReportPreviewView findPreview(String id) { return jdbc.sql("""
            SELECT preview.preview_id::text,definition.code,preview.dataset_id::text,preview.content_snapshot::text,preview.expires_at,preview.version
            FROM reporting.report_preview preview JOIN reporting.report_definition definition ON definition.report_definition_id=preview.report_definition_id WHERE preview.preview_id=CAST(:id AS uuid)""").param("id",id).query((r,n)->view(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getTimestamp(5).toInstant(),r.getLong(6))).optional().orElse(null); }
    @Override public String findPreviewRegion(String previewId) {
        return jdbc.sql("""
                SELECT dataset.region_code FROM reporting.report_preview preview
                JOIN reporting.approved_dataset dataset ON dataset.dataset_id = preview.dataset_id
                WHERE preview.preview_id = CAST(:id AS uuid)
                """).param("id", previewId).query(String.class).optional().orElse(null);
    }
    private List<ReportParameterOptionsView.Option> options(String sql){ return jdbc.sql(sql).query((r,n)->new ReportParameterOptionsView.Option(r.getString(1),r.getString(2))).list(); }
    private List<ReportDefinitionView.Section> sections(long id){ return jdbc.sql("SELECT section_code,title,sort_order FROM reporting.report_definition_section WHERE report_definition_id=:id ORDER BY sort_order").param("id",id).query((r,n)->new ReportDefinitionView.Section(r.getString(1),r.getString(2),r.getInt(3))).list(); }
    private boolean exists(String sql,String code){return jdbc.sql(sql).param("code",code).query(Integer.class).optional().isPresent();}
    private boolean exists(String sql,String code,String level){return jdbc.sql(sql).param("code",code).param("level",level).query(Integer.class).optional().isPresent();}
    private String label(String sql,String code){return jdbc.sql(sql).param("code",code).query(String.class).single();}
    private long approvedCount(String domain,ReportPreviewCommand c){
        String period="(SELECT starts_on FROM platform.business_period WHERE code=:period) AND (SELECT ends_on FROM platform.business_period WHERE code=:period)";
        String production="SELECT count(*) FROM production.production_record WHERE product_code=:product AND region_code=:region AND status_code='APPROVED' AND survey_date BETWEEN "+period;
        String market="SELECT count(*) FROM market.market_record WHERE product_code=:product AND region_code=:region AND status_code='APPROVED' AND trade_date BETWEEN "+period;
        String logistics="SELECT count(*) FROM logistics.route_event WHERE product_code=:product AND (origin_region_code=:region OR destination_region_code=:region) AND status_code='APPROVED' AND collection_date BETWEEN "+period;
        String supply="SELECT count(*) FROM supply.calculation_run WHERE product_code=:product AND region_code=:region AND result_state='FORMAL' AND created_at::date BETWEEN "+period;
        String sql=switch(domain){case "PRODUCTION"->production;case "MARKET"->market;case "LOGISTICS"->logistics;case "SUPPLY"->supply;case "SUBMISSION"->"SELECT ("+production+")+("+market+")+("+logistics+")";case "COMPREHENSIVE"->"SELECT ("+production+")+("+market+")+("+logistics+")+("+supply+")";default->"SELECT 0";};
        return jdbc.sql(sql).param("product",c.productCode()).param("region",c.regionCode()).param("period",c.periodCode()).query(Long.class).single();
    }
    private String parameters(ReportPreviewCommand c){return "{\"definitionCode\":\""+c.definitionCode()+"\",\"productCode\":\""+c.productCode()+"\",\"regionCode\":\""+c.regionCode()+"\",\"periodCode\":\""+c.periodCode()+"\"}";}
    private void audit(String type,String id,String action,String actor,Instant now,String detail){jdbc.sql("INSERT INTO reporting.report_audit_event(audit_event_id,aggregate_type,aggregate_id,action_code,actor,occurred_at,detail) VALUES(CAST(:event AS uuid),:type,CAST(:id AS uuid),:action,:actor,:now,CAST(:detail AS jsonb))").param("event",UUID.randomUUID().toString()).param("type",type).param("id",id).param("action",action).param("actor",actor).param("now",Timestamp.from(now)).param("detail",detail).update();}
    private ReportPreviewView view(String id,String definition,String dataset,String content,Instant expires,long version){try{JsonNode root=json.readTree(content);List<ReportPreviewView.Section> sections=new ArrayList<>();for(JsonNode node:root.path("sections"))sections.add(new ReportPreviewView.Section(node.path("code").asText(),node.path("title").asText(),node.path("body").asText()));return new ReportPreviewView(id,definition,dataset,root.path("title").asText(),root.path("dataCutoffLabel").asText(),List.of(new ReportPreviewView.Line("核定数据条数",root.path("approvedRecordCount").asText(),"服务端核定快照")),sections,expires,version,false);}catch(Exception e){throw new IllegalStateException(e);}}
    record DefinitionRow(long id,String code,String name,String businessDomain,String businessSubtype,String frequencyCode,int versionNo){}
    @Override public ReportExportView persistExport(ReportExportPersistence e){
        if (!exists("SELECT 1 FROM reporting.report_output_format WHERE format_code=:code AND enabled", e.formatCode())) throw new IllegalArgumentException("format");
        String id=UUID.randomUUID().toString();
        int written;
        try { written=jdbc.sql("""
                INSERT INTO reporting.report_export_task(export_task_id,preview_id,format_code,status_code,filename,content_type,content_digest,content,requested_by,requested_at)
                VALUES(CAST(:id AS uuid),CAST(:preview AS uuid),:format,'COMPLETED',:filename,:type,:digest,:content,:actor,:now)""")
            .param("id",id).param("preview",e.previewId()).param("format",e.formatCode()).param("filename",e.filename()).param("type",e.contentType()).param("digest",e.contentDigest()).param("content",new SqlParameterValue(Types.BINARY,e.content())).param("actor",e.actor()).param("now",Timestamp.from(e.now())).update(); }
        catch (org.springframework.dao.DataIntegrityViolationException exception) { throw new IllegalStateException("report export persistence failed", exception); }
        if(written!=1) throw new IllegalArgumentException("preview");
        audit("EXPORT",id,"EXPORTED",e.actor(),e.now(),"{\"previewId\":\""+e.previewId()+"\",\"formatCode\":\""+e.formatCode()+"\"}");
        return new ReportExportView(id,e.previewId(),e.formatCode(),e.filename(),e.contentType(),e.now());
    }
    @Override public ReportExportContent findExportContent(String exportTaskId) {
        return jdbc.sql("""
                SELECT export_task_id::text, filename, content_type, content
                FROM reporting.report_export_task
                WHERE export_task_id=CAST(:id AS uuid) AND status_code='COMPLETED'
                """).param("id", exportTaskId).query((row, index) -> new ReportExportContent(
                row.getString(1), row.getString(2), row.getString(3), row.getBytes(4))).optional().orElse(null);
    }
    @Override public String findExportRegion(String exportTaskId) {
        return jdbc.sql("""
                SELECT dataset.region_code FROM reporting.report_export_task export
                JOIN reporting.report_preview preview ON preview.preview_id = export.preview_id
                JOIN reporting.approved_dataset dataset ON dataset.dataset_id = preview.dataset_id
                WHERE export.export_task_id = CAST(:id AS uuid)
                """).param("id", exportTaskId).query(String.class).optional().orElse(null);
    }
    @Override public ReportPublicationView persistPublication(ReportPublicationPersistence p){
        Long current=jdbc.sql("SELECT version FROM reporting.report_preview WHERE preview_id=CAST(:id AS uuid) FOR UPDATE").param("id",p.previewId()).query(Long.class).optional().orElse(null);
        if(current==null || current!=p.expectedVersion()) throw new IllegalStateException("version");
        boolean exportMatches=jdbc.sql("SELECT 1 FROM reporting.report_export_task WHERE export_task_id=CAST(:id AS uuid) AND preview_id=CAST(:preview AS uuid) AND status_code='COMPLETED'").param("id",p.exportTaskId()).param("preview",p.previewId()).query(Integer.class).optional().isPresent();
        if(!exportMatches) throw new IllegalArgumentException("export");
        String id=UUID.randomUUID().toString();
        try { jdbc.sql("INSERT INTO reporting.report_publication(publication_id,preview_id,export_task_id,published_by,published_at,version) VALUES(CAST(:id AS uuid),CAST(:preview AS uuid),CAST(:export AS uuid),:actor,:now,:version)").param("id",id).param("preview",p.previewId()).param("export",p.exportTaskId()).param("actor",p.actor()).param("now",Timestamp.from(p.now())).param("version",current+1).update(); }
        catch (RuntimeException exception) { throw new IllegalStateException("publication",exception); }
        audit("PUBLICATION",id,"PUBLISHED",p.actor(),p.now(),"{\"previewId\":\""+p.previewId()+"\",\"exportTaskId\":\""+p.exportTaskId()+"\"}");
        return new ReportPublicationView(id,p.previewId(),p.exportTaskId(),p.now(),current+1);
    }
}
