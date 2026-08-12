UPDATE reporting.report_output_format
SET enabled = true
WHERE format_code = 'DOCX';

COMMENT ON TABLE reporting.approved_dataset IS
    'Immutable approved/formal source snapshot with filter scope, digest, exact cutoff, and audit identity carried by its preview and every export format.';
