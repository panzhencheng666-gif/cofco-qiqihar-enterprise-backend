#!/usr/bin/env python3
"""Read-only identity/scope check for the fixed formal local risk migration target.

This program has no migration, service-control, or apply command.
"""

import json
import os
import subprocess


QUERY = """
SELECT json_build_object(
  'database', current_database(),
  'serverAddress', host(inet_server_addr()),
  'serverPort', inet_server_port(),
  'transactionReadOnly', current_setting('transaction_read_only'),
  'latestVersion', (SELECT version FROM public.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1),
  'historyCount', (SELECT count(*) FROM public.flyway_schema_history),
  'failedCount', (SELECT count(*) FROM public.flyway_schema_history WHERE NOT success),
  'riskSchemaPresent', to_regnamespace('risk') IS NOT NULL,
  'riskMigrations', (SELECT coalesce(json_agg(json_build_object(
      'version', version, 'script', script, 'success', success) ORDER BY installed_rank), '[]'::json)
    FROM public.flyway_schema_history WHERE version IN ('214', '215', '216', '217', '222'))
);
"""


def probe(run=subprocess.run):
    result = {
        "kind": "formal-risk-migration-readonly-preflight",
        "dbScope": "BLOCKED",
        "applyAllowed": False,
        "target": "127.0.0.1:5432/qiqihar_enterprise_dev",
        "blockers": [],
        "remainingGates": [
            "Independent model approval governance for any future automatic activation",
            "Intervening V218-V221 market migrations require a separate ordered Flyway gate",
            "Same-window backup and isolated restore",
            "Verified write boundary",
            "Bounded formal migration executor and recovery procedure",
            "Post-migration readback and real business acceptance",
        ],
    }
    environment = os.environ.copy()
    for key in ("PGHOSTADDR", "PGHOST", "PGPORT", "PGDATABASE", "PGSERVICE", "PGOPTIONS"):
        environment.pop(key, None)
    environment["PGOPTIONS"] = (
        "-c default_transaction_read_only=on -c statement_timeout=5000 -c lock_timeout=1000"
    )
    environment["PGCONNECT_TIMEOUT"] = "3"
    command = [
        "psql", "-X", "-A", "-t", "-q", "-v", "ON_ERROR_STOP=1",
        "--host=127.0.0.1", "--port=5432", "--dbname=qiqihar_enterprise_dev",
        "-c", QUERY,
    ]
    try:
        completed = run(command, check=True, capture_output=True, text=True,
                        timeout=8, env=environment)
        observed = json.loads(completed.stdout.strip())
    except (OSError, ValueError, subprocess.SubprocessError):
        result["blockers"].append("QUERY_FAILED")
        return result

    if not isinstance(observed, dict):
        result["blockers"].append("INVALID_RESULT")
        return result
    result["snapshot"] = observed
    if (observed.get("database") != "qiqihar_enterprise_dev"
            or observed.get("serverAddress") != "127.0.0.1"
            or observed.get("serverPort") != 5432
            or observed.get("transactionReadOnly") != "on"):
        result["blockers"].append("TARGET_OR_READONLY_MISMATCH")
    if (observed.get("latestVersion") != "213"
            or observed.get("historyCount") != 213
            or observed.get("failedCount") != 0
            or observed.get("riskSchemaPresent") is not False
            or observed.get("riskMigrations") != []):
        result["blockers"].append("UNEXPECTED_MIGRATION_STATE")
    if not result["blockers"]:
        result["dbScope"] = "MATCH_PENDING_RISK_MIGRATIONS"
    return result


def main():
    result = probe()
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["dbScope"] == "MATCH_PENDING_RISK_MIGRATIONS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
