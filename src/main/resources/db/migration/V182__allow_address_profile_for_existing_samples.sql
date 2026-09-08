-- Legacy and logistics samples can maintain an address without inventing a business object type.
-- New master-data creation still validates its explicit object type in the application contract.
ALTER TABLE registry.formal_sample_point_profile ALTER COLUMN object_type_code DROP NOT NULL;
