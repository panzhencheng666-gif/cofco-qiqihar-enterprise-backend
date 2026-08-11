UPDATE platform.object_type
SET name = CASE code
    WHEN 'DEEP_PROCESSOR' THEN '深加工企业'
    WHEN 'BREEDING_FACTORY' THEN '养殖场'
    ELSE name
END
WHERE code IN ('DEEP_PROCESSOR', 'BREEDING_FACTORY');

CREATE VIEW platform.product_object_type_applicability AS
SELECT product_code, object_type_code
FROM platform.product_object_type;

COMMENT ON VIEW platform.product_object_type_applicability IS
    'Formal read contract for product-specific business object type applicability.';
