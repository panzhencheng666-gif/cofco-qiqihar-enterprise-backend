INSERT INTO platform.logistics_action_applicability(product_code, status_code, action_code)
SELECT product.code, 'RETURNED', 'SUBMIT'
FROM platform.product product
ON CONFLICT (product_code, status_code, action_code) DO NOTHING;
