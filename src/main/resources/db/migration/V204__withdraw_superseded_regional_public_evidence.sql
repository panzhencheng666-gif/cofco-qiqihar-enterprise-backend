-- Preserve withdrawn public evidence for audit; it is not eligible for display or estimation.
ALTER TABLE production.regional_public_indicator
  ADD COLUMN current_evidence boolean NOT NULL DEFAULT true;

-- The Heihe 2025 paragraph gives oilseed output after the aggregate economic-crop area.
-- The old parser assigned that child output to the aggregate and derived a false yield.
UPDATE production.regional_public_indicator
SET current_evidence=false
WHERE source_id='heihe-annual-agriculture' AND data_year=2025
  AND indicator_id LIKE 'auto-%'
  AND label IN ('经济作物产量','经济作物平均单产');
