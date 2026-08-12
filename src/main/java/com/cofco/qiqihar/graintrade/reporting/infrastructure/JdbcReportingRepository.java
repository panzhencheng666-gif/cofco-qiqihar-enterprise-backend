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
import tools.jackson.databind.node.ObjectNode;

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
        ApprovedDatasetSnapshot dataset = approvedDatasetSnapshot(definition.businessDomain(), command);
        long count = dataset.approvedRecordCount();
        Instant cutoff = dataset.dataCutoff();
        ObjectNode summaryNode = json.createObjectNode();
        summaryNode.put("businessDomain", definition.businessDomain());
        summaryNode.put("approvedRecordCount", count);
        if (cutoff == null) summaryNode.putNull("dataCutoff");
        else summaryNode.put("dataCutoff", cutoff.toString());
        summaryNode.set("sources", json.readTree(dataset.sourceManifestJson()));
        String summary = summaryNode.toString();
        return new ReportPreviewMaterial(new ReportDefinitionView(definition.code(),definition.name(),definition.businessDomain(),definition.businessSubtype(),definition.frequencyCode(),definition.versionNo(), sections(definition.id())),
                label("SELECT name FROM platform.product WHERE code=:code",command.productCode()), label("SELECT name FROM platform.region WHERE code=:code",command.regionCode()),
                label("SELECT name FROM platform.business_period WHERE code=:code",command.periodCode()),summary,count,cutoff);
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

    private ApprovedDatasetSnapshot approvedDatasetSnapshot(String domain, ReportPreviewCommand c) {
        String regionScope = """
                WITH RECURSIVE selected_regions(code) AS (
                    SELECT code FROM platform.region WHERE code=:region
                    UNION
                    SELECT child.code FROM platform.region child
                    JOIN selected_regions parent ON child.parent_code=parent.code
                )
                """;
        String period = "(SELECT starts_on FROM platform.business_period WHERE code=:period)"
                + " AND (SELECT ends_on FROM platform.business_period WHERE code=:period)";
        String production = regionScope + """
                SELECT count(*) AS approved_count,max(record.reported_at) AS data_cutoff,
                  COALESCE(jsonb_agg(jsonb_build_object(
                  'sourceRecordId',record.record_id,'sourceVersion',record.version,
                  'reportedAt',record.reported_at,
                  'contentSha256',encode(sha256(convert_to(
                    to_jsonb(record)::text
                    || COALESCE((SELECT jsonb_agg(to_jsonb(metadata) ORDER BY metadata.field_code)::text
                      FROM production.production_record_submission_metadata metadata
                      WHERE metadata.record_id=record.record_id),'[]')
                    || jsonb_build_object(
                      'quality',COALESCE((SELECT jsonb_agg(to_jsonb(fact) ORDER BY fact.quality_code)
                        FROM production.production_record_quality fact
                        WHERE fact.record_id=record.record_id),'[]'::jsonb),
                      'cost',COALESCE((SELECT jsonb_agg(to_jsonb(fact) ORDER BY fact.cost_code)
                        FROM production.production_record_cost fact
                        WHERE fact.record_id=record.record_id),'[]'::jsonb),
                      'insurance',COALESCE((SELECT jsonb_agg(to_jsonb(fact) ORDER BY fact.insurance_code)
                        FROM production.production_record_insurance fact
                        WHERE fact.record_id=record.record_id),'[]'::jsonb),
                      'subsidy',COALESCE((SELECT jsonb_agg(to_jsonb(fact) ORDER BY fact.subsidy_code)
                        FROM production.production_record_subsidy fact
                        WHERE fact.record_id=record.record_id),'[]'::jsonb))::text,'UTF8')),'hex'))
                  ORDER BY record.record_id),'[]'::jsonb)::text AS source_manifest
                FROM production.production_record record
                WHERE record.product_code=:product
                  AND record.region_code IN (SELECT code FROM selected_regions)
                  AND record.status_code='APPROVED'
                  AND record.survey_period_governance_state='CONFIRMED'
                  AND record.survey_date BETWEEN %s
                  AND (CAST(:cultivar AS varchar) IS NULL OR record.cultivar_code=:cultivar OR EXISTS (
                    SELECT 1 FROM production.production_record_submission_metadata metadata
                    WHERE metadata.record_id=record.record_id AND metadata.field_code='PROD_CULTIVAR_NAME'
                      AND (metadata.value=:cultivar OR metadata.value=(SELECT cultivar.name
                        FROM platform.cultivar cultivar WHERE cultivar.code=:cultivar
                          AND cultivar.product_code=record.product_code))))
                """.formatted(period);
        String market = regionScope + """
                SELECT count(*) AS approved_count,max(record.reported_at) AS data_cutoff,
                  COALESCE(jsonb_agg(jsonb_build_object(
                  'sourceRecordId',record.record_id,'sourceVersion',record.version,
                  'reportedAt',record.reported_at,
                  'contentSha256',encode(sha256(convert_to(
                    to_jsonb(record)::text
                    || COALESCE((SELECT jsonb_agg(to_jsonb(value) ORDER BY value.field_code)::text
                      FROM market.market_record_core_value value
                      WHERE value.record_id=record.record_id),'[]')
                    || COALESCE((SELECT jsonb_agg(to_jsonb(fact) ORDER BY fact.fact_code)::text
                      FROM market.market_record_fact fact
                      WHERE fact.record_id=record.record_id),'[]'),'UTF8')),'hex'))
                  ORDER BY record.record_id),'[]'::jsonb)::text AS source_manifest
                FROM market.market_record record
                WHERE record.product_code=:product
                  AND record.region_code IN (SELECT code FROM selected_regions)
                  AND record.status_code='APPROVED'
                  AND record.survey_period_governance_state='CONFIRMED'
                  AND record.trade_date BETWEEN %s
                  AND (CAST(:cultivar AS varchar) IS NULL OR EXISTS (
                    SELECT 1 FROM market.market_record_core_value value
                    WHERE value.record_id=record.record_id AND value.field_code='MKT_CULTIVAR_NAME'
                      AND (value.value=:cultivar OR value.value=(SELECT cultivar.name
                        FROM platform.cultivar cultivar WHERE cultivar.code=:cultivar
                          AND cultivar.product_code=record.product_code))))
                """.formatted(period);
        String logistics = regionScope + """
                SELECT count(*) AS approved_count,max(event.reported_at) AS data_cutoff,
                  COALESCE(jsonb_agg(jsonb_build_object(
                  'sourceRecordId',event.event_id,'sourceVersion',event.version,
                  'reportedAt',event.reported_at,
                  'contentSha256',encode(sha256(convert_to(
                    to_jsonb(event)::text
                    || COALESCE((SELECT jsonb_agg(to_jsonb(fact) ORDER BY fact.fact_code)::text
                      FROM logistics.route_fact fact WHERE fact.event_id=event.event_id),'[]'),
                    'UTF8')),'hex')) ORDER BY event.event_id),'[]'::jsonb)::text AS source_manifest
                FROM logistics.route_event event
                WHERE event.product_code=:product
                  AND (event.origin_region_code IN (SELECT code FROM selected_regions)
                    OR event.destination_region_code IN (SELECT code FROM selected_regions))
                  AND event.status_code='APPROVED'
                  AND event.survey_period_governance_state='CONFIRMED'
                  AND event.collection_date BETWEEN %s
                  AND CAST(:cultivar AS varchar) IS NULL
                """.formatted(period);
        String supply = regionScope + """
                SELECT count(*) AS approved_count,max(run.created_at) AS data_cutoff,
                  COALESCE(jsonb_agg(jsonb_build_object(
                  'sourceRecordId',run.calculation_run_id,'sourceVersion',run.version,
                  'reportedAt',run.created_at,
                  'contentSha256',encode(sha256(convert_to(to_jsonb(run)::text,'UTF8')),'hex'))
                  ORDER BY run.calculation_run_id),'[]'::jsonb)::text AS source_manifest
                FROM supply.calculation_run run
                WHERE run.product_code=:product
                  AND run.region_code IN (SELECT code FROM selected_regions)
                  AND run.result_state='PUBLISHED'
                  AND run.temporal_governance_state='CONFIRMED'
                  AND run.created_at::date BETWEEN %s
                  AND CAST(:cultivar AS varchar) IS NULL
                """.formatted(period);
        String sql = switch (domain) {
            case "PRODUCTION" -> production;
            case "MARKET" -> market;
            case "LOGISTICS" -> logistics;
            case "SUPPLY" -> supply;
            default -> throw new IllegalArgumentException("unsupported report domain");
        };
        String cultivar = c.cultivarCode() == null || c.cultivarCode().isBlank()
                ? null : c.cultivarCode().strip();
        return jdbc.sql(sql).param("product", c.productCode()).param("region", c.regionCode())
                .param("period", c.periodCode()).param("cultivar", cultivar)
                .query((row, index) -> new ApprovedDatasetSnapshot(
                        row.getLong("approved_count"),
                        row.getTimestamp("data_cutoff") == null
                                ? null : row.getTimestamp("data_cutoff").toInstant(),
                        row.getString("source_manifest")))
                .single();
    }
    private record ApprovedDatasetSnapshot(
            long approvedRecordCount, Instant dataCutoff, String sourceManifestJson) {}
    private String parameters(ReportPreviewCommand c){
        ObjectNode parameters=json.createObjectNode();
        parameters.put("definitionCode",c.definitionCode());
        parameters.put("productCode",c.productCode());
        if(c.cultivarCode()!=null && !c.cultivarCode().isBlank()) parameters.put("cultivarCode",c.cultivarCode().strip());
        parameters.put("regionLevel",c.regionLevel());
        parameters.put("regionCode",c.regionCode());
        parameters.put("periodCode",c.periodCode());
        return parameters.toString();
    }
    private void audit(String type,String id,String action,String actor,Instant now,String detail){jdbc.sql("INSERT INTO reporting.report_audit_event(audit_event_id,aggregate_type,aggregate_id,action_code,actor,occurred_at,detail) VALUES(CAST(:event AS uuid),:type,CAST(:id AS uuid),:action,:actor,:now,CAST(:detail AS jsonb))").param("event",UUID.randomUUID().toString()).param("type",type).param("id",id).param("action",action).param("actor",actor).param("now",Timestamp.from(now)).param("detail",detail).update();}
    private ReportPreviewView view(String id,String definition,String dataset,String content,Instant expires,long version){try{JsonNode root=json.readTree(content);List<ReportPreviewView.Section> sections=new ArrayList<>();for(JsonNode node:root.path("sections"))sections.add(new ReportPreviewView.Section(node.path("code").asText(),node.path("title").asText(),node.path("body").asText()));List<ReportPreviewView.Line> lines=List.of(new ReportPreviewView.Line("核定数据条数",root.path("approvedRecordCount").asText(),"服务端核定快照"),new ReportPreviewView.Line("报告范围",root.path("scopeLabel").asText(),"所选地区、产品与期间"),new ReportPreviewView.Line("精确数据截止",root.path("dataCutoff").asText(),"所纳入正式来源的最晚填报时间"),new ReportPreviewView.Line("审计编号",root.path("auditNumber").asText(),"不可变核定数据集编号"),new ReportPreviewView.Line("数据分级",root.path("classification").asText(),"按内部数据管理"),new ReportPreviewView.Line("计算口径",root.path("formula").asText(),"服务端核定口径"),new ReportPreviewView.Line("正式来源",root.path("sourcePath").asText(),"正式业务来源"),new ReportPreviewView.Line("口径版本",root.path("calculationVersion").asText(),"报表定义版本"));return new ReportPreviewView(id,definition,dataset,root.path("title").asText(),root.path("dataCutoffLabel").asText(),lines,sections,expires,version,false);}catch(Exception e){throw new IllegalStateException(e);}}
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
