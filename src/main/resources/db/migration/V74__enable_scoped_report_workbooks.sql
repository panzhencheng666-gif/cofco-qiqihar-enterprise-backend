UPDATE reporting.report_output_format
SET enabled = true
WHERE format_code = 'XLSX';

COMMENT ON COLUMN reporting.report_output_format.enabled IS
    'Only formats generated from the immutable server-side scoped preview may be enabled.';
