package com.cofco.qiqihar.graintrade.overview.application;

import com.cofco.qiqihar.graintrade.importing.application.ImportWorkbookErrors;
import com.cofco.qiqihar.graintrade.importing.infrastructure.BusinessImportWorkbook;
import com.cofco.qiqihar.graintrade.shared.application.ClientRequestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OperationalFacilityImportService {
    private final OperationalFacilityService facilities;

    public OperationalFacilityImportService(OperationalFacilityService facilities) {
        this.facilities = facilities;
    }

    public byte[] template() {
        return BusinessImportWorkbook.create(
                OperationalFacilityWorkbook.template(), List.of(),
                new BusinessImportWorkbook.WorkbookOptions(
                        "库点批量填报", null, null, java.util.Set.of(),
                        List.of(
                                List.of("导入规则", "整批校验通过后统一入库；任一行有误时不会写入任何库点。"),
                                List.of("地图位置", "经纬度可留空；填写时必须同时填写，经度在前、纬度在后。"))));
    }

    public OperationalFacilityImportResult importWorkbook(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 2 * 1024 * 1024) {
            throw invalidWorkbook("上传文件为空或超过 2MB");
        }
        BusinessImportWorkbook.ImportSheet sheet;
        try {
            sheet = BusinessImportWorkbook.readDraft(
                    bytes, OperationalFacilityWorkbook.template(), OperationalFacilityWorkbook.MAX_ROWS);
        } catch (IllegalArgumentException exception) {
            throw ImportWorkbookErrors.invalid(
                    exception, "INVALID_OPERATIONAL_FACILITY_IMPORT_FORMAT");
        }
        if (sheet.rows().isEmpty()) {
            return new OperationalFacilityImportResult(0, List.of(
                    new OperationalFacilityImportResult.RowError(2, "整行", "请至少填写一条库点记录")));
        }

        List<OperationalFacilityImportResult.RowError> errors = new ArrayList<>();
        List<OperationalStorageFacilityDraft> drafts = new ArrayList<>();
        for (int index = 0; index < sheet.rows().size(); index++) {
            int rowNumber = index + 2;
            List<String> row = sheet.rows().get(index);
            try {
                OperationalStorageFacilityDraft draft = draft(row, rowNumber);
                drafts.add(facilities.validateForImport(draft));
            } catch (RowException exception) {
                errors.add(new OperationalFacilityImportResult.RowError(
                        rowNumber, exception.field, exception.getMessage()));
            } catch (ClientRequestException exception) {
                errors.add(new OperationalFacilityImportResult.RowError(
                        rowNumber, fieldFor(exception.getMessage()), exception.getMessage()));
            }
        }
        if (!errors.isEmpty()) return new OperationalFacilityImportResult(0, errors);
        facilities.importValidated(drafts);
        return new OperationalFacilityImportResult(drafts.size(), List.of());
    }

    private static OperationalStorageFacilityDraft draft(List<String> row, int rowNumber) {
        if (row.size() != OperationalFacilityWorkbook.HEADERS.size()) {
            throw new RowException("整行", "第 " + rowNumber + " 行列数与模板不一致");
        }
        return new OperationalStorageFacilityDraft(
                required(row, 0, "库点名称"),
                relation(row.get(1)),
                required(row, 2, "所在地区代码"),
                required(row, 3, "详细地址"),
                decimal(row.get(4), "经度"),
                decimal(row.get(5), "纬度"),
                status(row.get(6)),
                decimal(row.get(7), "仓容（吨）"),
                date(row.get(8), "仓容日期"),
                date(row.get(9), "合作开始日期"),
                date(row.get(10), "合作结束日期"),
                0);
    }

    private static String required(List<String> row, int index, String field) {
        String value = row.get(index).trim();
        if (value.isEmpty()) throw new RowException(field, field + "不能为空");
        return value;
    }

    private static String relation(String value) {
        return switch (value.trim()) {
            case "自有库点" -> "OWNED";
            case "租赁库点" -> "LEASED";
            case "历史租赁库点" -> "HISTORICAL_LEASED";
            default -> throw new RowException("库点类型", "请选择自有库点、租赁库点或历史租赁库点");
        };
    }

    private static String status(String value) {
        return switch (value.trim()) {
            case "运营中" -> "ACTIVE";
            case "已停用" -> "INACTIVE";
            case "尚未核定" -> "UNKNOWN";
            default -> throw new RowException("运营状态", "请选择运营中、已停用或尚未核定");
        };
    }

    private static BigDecimal decimal(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            throw new RowException(field, field + "必须是有效数字");
        }
    }

    private static LocalDate date(String value, String field) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException exception) {
            throw new RowException(field, field + "必须采用 YYYY-MM-DD 格式");
        }
    }

    private static String fieldFor(String message) {
        if (message == null) return "整行";
        for (String field : List.of("库点名称", "库点类型", "所在地区", "详细地址", "经度和纬度",
                "经纬度", "运营状态", "仓容", "有效期结束日期")) {
            if (message.contains(field)) return field;
        }
        return "整行";
    }

    private static ClientRequestException invalidWorkbook(String message) {
        return new ClientRequestException("INVALID_OPERATIONAL_FACILITY_IMPORT_FORMAT", message);
    }

    private static final class RowException extends RuntimeException {
        private final String field;

        private RowException(String field, String message) {
            super(message);
            this.field = field;
        }
    }
}
