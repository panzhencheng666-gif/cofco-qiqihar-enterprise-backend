-- V118's private subject-resolution applier verifies whether a market record
-- is an inventory row by reading its normalized facts. V130 deliberately owns
-- the review-bound SECURITY DEFINER entry with the NOLOGIN migration role, so
-- that owner needs the same read needed by the nested validation. The runtime
-- role already has this business read and receives no new privilege here.
GRANT SELECT ON TABLE market.market_record_fact TO qiqihar_migration_owner;
