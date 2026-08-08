ALTER TABLE platform.geography_import_batch
    DROP CONSTRAINT geography_import_batch_check;

ALTER TABLE platform.geography_import_batch
    ADD CONSTRAINT geography_import_batch_coordinate_count_check
    CHECK (
        coordinate_count >= village_count
        AND coordinate_count <= village_count + township_count
    );

COMMENT ON COLUMN platform.geography_import_batch.coordinate_count IS
    'Number of governed region points in the batch. A complete township-and-village batch equals township_count plus village_count.';

COMMENT ON TABLE platform.region_location IS
    'Source-attributed township and village points. Original CGCS2000 coordinates and transformed WGS84 presentation points are both retained.';
