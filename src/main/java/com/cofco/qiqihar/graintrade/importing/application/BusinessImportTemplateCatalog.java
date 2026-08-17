package com.cofco.qiqihar.graintrade.importing.application;

import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Resolves internal object-type applicability without exposing it as extra user templates. */
@Component
public class BusinessImportTemplateCatalog {
    private final JdbcClient jdbc;

    public BusinessImportTemplateCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ObjectTypeOption> objectTypes(String domainCode, String productCode) {
        List<ObjectTypeOption> options = jdbc.sql("""
                        SELECT object_type.code, object_type.name
                        FROM platform.product_object_type applicability
                        JOIN platform.object_type object_type
                          ON object_type.code=applicability.object_type_code
                        WHERE applicability.product_code=:productCode
                          AND object_type.business_domain=:domainCode
                        ORDER BY object_type.sort_order,object_type.code
                        """)
                .param("productCode", productCode)
                .param("domainCode", domainCode)
                .query((row, ignored) -> new ObjectTypeOption(
                        row.getString("code"), row.getString("name")))
                .list();
        if (options.isEmpty()) {
            throw new ClientRequestException("INVALID_IMPORT_CONTEXT", "导入模板的品种或业务类型无效");
        }
        return List.copyOf(options);
    }

    public record ObjectTypeOption(String code, String label) {}
}
