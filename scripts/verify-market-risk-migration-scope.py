#!/usr/bin/env python3
"""Keep pending risk migrations out of a market-only local install."""

import os
import stat
import subprocess
import sys
from pathlib import Path


RISK_MIGRATIONS = {
    "214": "V214__create_inventory_risk_foundation.sql",
    "215": "V215__operate_daily_risk_ai_training.sql",
    "216": "V216__automate_risk_model_promotion.sql",
    "217": "V217__isolate_risk_schema_runtime.sql",
}
DATABASE = "qiqihar_enterprise_dev"
DATABASE_URL = f"jdbc:postgresql://127.0.0.1:5432/{DATABASE}"


def require_managed_database_target():
    # The launch agent exports this file after inheriting its own environment.
    # Require an explicit target in that file; the installer's shell environment
    # alone cannot establish the target of the separately launched agent.
    config = Path(os.environ.get("COFCO_ENTERPRISE_LOCAL_ENV_FILE") or
                  Path.home() / ".config/cofco-qiqihar-enterprise/local-runtime.env")
    if config.is_symlink() or not config.is_file():
        raise ValueError("Managed database target requires a regular runtime config")
    if stat.S_IMODE(config.stat().st_mode) & 0o077:
        raise ValueError("Managed runtime config has unsafe permissions")
    target = None
    for line in config.read_text(encoding="utf-8").split("\n"):
        line = line.removesuffix("\r")
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator:
            raise ValueError("Managed runtime config contains an invalid line")
        if key == "QIQIHAR_DB_URL":
            target = value  # Match the launcher's literal, last-value-wins parsing.
    if target is None:
        raise ValueError("Managed database target must be explicit in runtime config")
    if target != DATABASE_URL:
        raise ValueError("Managed database target differs from the supported formal local target")
    if any(os.environ.get(key) for key in (
        "SPRING_DATASOURCE_URL", "SPRING_FLYWAY_URL", "SPRING_APPLICATION_JSON"
    )):
        raise ValueError("Unsupported Spring database override during managed preflight")


def installed_migrations():
    require_managed_database_target()
    query = (
        "SELECT version || '|' || script FROM public.flyway_schema_history "
        "WHERE success AND version IN ('214','215','216','217') "
        "ORDER BY installed_rank"
    )
    result = subprocess.run(
        ["psql", "--no-psqlrc", "--no-password", "--tuples-only", "--no-align",
         "--set=ON_ERROR_STOP=1", "--host=127.0.0.1", "--port=5432",
         f"--dbname={DATABASE}", "--command", query],
        capture_output=True, text=True, timeout=10, check=False,
    )
    if result.returncode:
        raise ValueError("Formal database migration history is unavailable")
    rows = {}
    for line in result.stdout.splitlines():
        version, separator, script = line.partition("|")
        if not separator or version not in RISK_MIGRATIONS or version in rows:
            raise ValueError("Formal database migration history is ambiguous")
        rows[version] = script
    return rows


def verify(source_backend):
    migrations = source_backend / "src/main/resources/db/migration"
    proposed = {
        version: name for version, name in RISK_MIGRATIONS.items()
        if (migrations / name).is_file()
    }
    if not proposed:
        return
    applied = installed_migrations()
    pending = [name for version, name in proposed.items() if applied.get(version) != name]
    if pending:
        raise ValueError(
            "Market-only install would introduce risk migrations: "
            + ", ".join(pending)
            + "; complete the separate risk release gate first"
        )


if __name__ == "__main__":
    try:
        if len(sys.argv) != 2:
            raise ValueError("One backend source path is required")
        verify(Path(sys.argv[1]))
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(f"Market release risk-scope preflight failed: {error}", file=sys.stderr)
        sys.exit(1)
    print("Market release risk-scope preflight passed")
