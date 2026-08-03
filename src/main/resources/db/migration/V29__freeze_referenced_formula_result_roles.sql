-- A formula version referenced by a calculation run is a complete immutable DAG.
-- V27 protected result-role updates/deletes and V28 protected term inserts; this
-- closes the remaining result-node insertion path.
CREATE TRIGGER formula_result_role_referenced_insert_immutable BEFORE INSERT
    ON supply.formula_result_role FOR EACH ROW
    EXECUTE FUNCTION supply.reject_referenced_formula_change();
