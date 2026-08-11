package com.cofco.qiqihar.graintrade.masterdata.infrastructure;

import com.cofco.qiqihar.graintrade.masterdata.application.MasterDataRepository;
import com.cofco.qiqihar.graintrade.masterdata.domain.BusinessBatch;
import com.cofco.qiqihar.graintrade.masterdata.domain.BusinessPeriod;
import com.cofco.qiqihar.graintrade.masterdata.domain.Cultivar;
import com.cofco.qiqihar.graintrade.masterdata.domain.FieldDefinition;
import com.cofco.qiqihar.graintrade.masterdata.domain.ObjectType;
import com.cofco.qiqihar.graintrade.masterdata.domain.PageDefaultContext;
import com.cofco.qiqihar.graintrade.masterdata.domain.PageDefinition;
import com.cofco.qiqihar.graintrade.masterdata.domain.Product;
import com.cofco.qiqihar.graintrade.masterdata.domain.Region;
import com.cofco.qiqihar.graintrade.masterdata.domain.SupplySurveyPeriod;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMasterDataRepository implements MasterDataRepository {

    private final JdbcClient jdbc;

    public JdbcMasterDataRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public List<Region> findRegions() {
        return jdbc.sql("""
                        SELECT code, name, parent_code, administrative_level
                        FROM platform.region
                        ORDER BY sort_order
                        """)
                .query((row, rowNumber) -> new Region(
                        row.getString("code"),
                        row.getString("name"),
                        row.getString("parent_code"),
                        row.getString("administrative_level")))
                .list();
    }

    @Override
    public List<Region> findRegionChildren(String parentCode) {
        if (parentCode == null) {
            return jdbc.sql("""
                            SELECT code, name, parent_code, administrative_level
                            FROM platform.region
                            WHERE parent_code IS NULL
                            ORDER BY sort_order
                            """)
                    .query(this::region)
                    .list();
        }
        return jdbc.sql("""
                        SELECT code, name, parent_code, administrative_level
                        FROM platform.region
                        WHERE parent_code = :parentCode
                        ORDER BY sort_order
                        """)
                .param("parentCode", parentCode)
                .query(this::region)
                .list();
    }

    private Region region(java.sql.ResultSet row, int rowNumber) throws java.sql.SQLException {
        return new Region(
                row.getString("code"),
                row.getString("name"),
                row.getString("parent_code"),
                row.getString("administrative_level"));
    }

    @Override
    public List<Region> findRegionPath(String regionCode) {
        return jdbc.sql("""
                        WITH RECURSIVE region_path AS (
                            SELECT code, name, parent_code, administrative_level, 0 AS depth
                            FROM platform.region
                            WHERE code = :regionCode
                            UNION ALL
                            SELECT parent.code,
                                   parent.name,
                                   parent.parent_code,
                                   parent.administrative_level,
                                   child.depth + 1
                            FROM platform.region parent
                            JOIN region_path child ON child.parent_code = parent.code
                        )
                        SELECT code, name, parent_code, administrative_level
                        FROM region_path
                        ORDER BY depth DESC
                        """)
                .param("regionCode", regionCode)
                .query(this::region)
                .list();
    }

    @Override
    public List<Product> findProducts() {
        return jdbc.sql("SELECT code, name FROM platform.product ORDER BY sort_order")
                .query((row, rowNumber) -> new Product(row.getString("code"), row.getString("name")))
                .list();
    }

    @Override
    public List<Product> findProductsWithPageDefinition(String domain, String pageKind) {
        return jdbc.sql("""
                        SELECT product.code, product.name
                        FROM platform.product product
                        JOIN platform.page_presentation presentation
                          ON presentation.product_code = product.code
                        WHERE presentation.business_domain = :domain
                          AND presentation.page_kind = :pageKind
                        ORDER BY product.sort_order
                        """)
                .param("domain", domain)
                .param("pageKind", pageKind)
                .query((row, rowNumber) -> new Product(row.getString("code"), row.getString("name")))
                .list();
    }

    @Override
    public List<Cultivar> findCultivarsByProductCode(String productCode) {
        return jdbc.sql("""
                        SELECT code, name, product_code
                        FROM platform.cultivar
                        WHERE product_code = :productCode
                        ORDER BY sort_order
                        """)
                .param("productCode", productCode)
                .query((row, rowNumber) -> new Cultivar(
                        row.getString("code"), row.getString("name"), row.getString("product_code")))
                .list();
    }

    @Override
    public List<ObjectType> findObjectTypes(String productCode, String domain) {
        return jdbc.sql("""
                        SELECT object_type.code, object_type.name, object_type.business_domain
                        FROM platform.object_type object_type
                        JOIN platform.product_object_type_applicability applicability
                          ON applicability.object_type_code = object_type.code
                        WHERE applicability.product_code = :productCode
                          AND object_type.business_domain = :domain
                        ORDER BY object_type.sort_order
                        """)
                .param("productCode", productCode)
                .param("domain", domain)
                .query((row, rowNumber) -> new ObjectType(
                        row.getString("code"),
                        row.getString("name"),
                        row.getString("business_domain")))
                .list();
    }

    @Override
    public List<BusinessPeriod> findBusinessPeriods() {
        return jdbc.sql("""
                        SELECT period.code,
                               period.name,
                               period.starts_on,
                               period.ends_on,
                               period.marketing_year_code,
                               marketing_year.name AS marketing_year_name
                        FROM platform.business_period period
                        JOIN platform.marketing_year marketing_year
                          ON marketing_year.code = period.marketing_year_code
                        ORDER BY period.sort_order
                        """)
                .query((row, rowNumber) -> new BusinessPeriod(
                        row.getString("code"),
                        row.getString("name"),
                        row.getObject("starts_on", java.time.LocalDate.class),
                        row.getObject("ends_on", java.time.LocalDate.class),
                        row.getString("marketing_year_code"),
                        row.getString("marketing_year_name")))
                .list();
    }

    @Override
    public List<SupplySurveyPeriod> findSupplySurveyPeriods() {
        return jdbc.sql("""
                        SELECT period.code,period.name,period.survey_year,period.survey_quarter,
                               period.precision,period.marketing_year_code,
                               marketing_year.name AS marketing_year_name
                        FROM platform.supply_survey_period period
                        JOIN platform.marketing_year marketing_year ON marketing_year.code=period.marketing_year_code
                        ORDER BY period.survey_year DESC,period.survey_quarter NULLS FIRST
                        """)
                .query((row, rowNumber) -> new SupplySurveyPeriod(
                        row.getString("code"), row.getString("name"), row.getInt("survey_year"),
                        row.getString("survey_quarter"), row.getString("precision"),
                        row.getString("marketing_year_code"), row.getString("marketing_year_name")))
                .list();
    }

    @Override
    public List<BusinessBatch> findBusinessBatchesByPeriodCode(String businessPeriodCode) {
        return jdbc.sql("""
                        SELECT code, name, business_period_code
                        FROM platform.business_batch
                        WHERE business_period_code = :businessPeriodCode
                        ORDER BY sort_order
                        """)
                .param("businessPeriodCode", businessPeriodCode)
                .query((row, rowNumber) -> new BusinessBatch(
                        row.getString("code"),
                        row.getString("name"),
                        row.getString("business_period_code")))
                .list();
    }

    @Override
    public Optional<PageDefinition> findPageDefinition(String productCode, String domain, String pageKind) {
        Boolean exists = jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM platform.page_definition
                            WHERE product_code = :productCode
                              AND business_domain = :domain
                              AND page_kind = :pageKind
                        )
                        """)
                .param("productCode", productCode)
                .param("domain", domain)
                .param("pageKind", pageKind)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(exists)) {
            return Optional.empty();
        }

        List<FieldDefinition> fields = jdbc.sql("""
                        SELECT field.code, field.name, field.value_type, link.sort_order
                        FROM platform.page_definition_field link
                        JOIN platform.field_definition field ON field.code = link.field_code
                        WHERE link.product_code = :productCode
                          AND link.business_domain = :domain
                          AND link.page_kind = :pageKind
                        ORDER BY link.sort_order
                        """)
                .param("productCode", productCode)
                .param("domain", domain)
                .param("pageKind", pageKind)
                .query((row, rowNumber) -> new FieldDefinition(
                        row.getString("code"),
                        row.getString("name"),
                        row.getString("value_type"),
                        row.getInt("sort_order")))
                .list();
        PageDefaultContext defaultContext = findDefaultContext(domain, pageKind).orElse(null);
        return Optional.of(new PageDefinition(productCode, domain, pageKind, fields, defaultContext));
    }

    private Optional<PageDefaultContext> findDefaultContext(String domain, String pageKind) {
        return jdbc.sql("""
                        SELECT default_product_code, default_business_period_code, default_business_batch_code
                        FROM platform.page_default_context
                        WHERE business_domain = :domain AND page_kind = :pageKind
                        """)
                .param("domain", domain)
                .param("pageKind", pageKind)
                .query((row, rowNumber) -> new PageDefaultContext(
                        row.getString("default_product_code"),
                        row.getString("default_business_period_code"),
                        row.getString("default_business_batch_code")))
                .optional();
    }
}
