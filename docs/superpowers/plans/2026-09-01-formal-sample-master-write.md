# Formal Sample Point Master Write Implementation Plan

1. Add failing REST integration tests for create, update, persistence requery, permissions, boundary and coordinate validation, optimistic locking, classification replacement, rollback, audit/outbox, and annual-network isolation.
2. Add migration V163 for the stable profile table, runtime grants, and `FORMAL_SAMPLE_MANAGE` permission.
3. Extend the formal-sample point view and repository with profile joins, validation lookups, transactional insert, and versioned update.
4. Add service/controller validation, authorization, coordinate guarding, audit/outbox emission, and `Location` response handling.
5. Run the focused tests, migration upgrade tests, required repository gate, inspect the diff, obtain read-only code review, then commit, push, open a draft PR, and wait for one native CI result for the pushed head.
