#!/usr/bin/env bash
set -euo pipefail

jar=${1:-/release/risk-intelligence-service.jar}
exec java \
  -Dloader.main=com.cofco.qiqihar.riskintelligence.operations.RiskMigrationRunner \
  -cp "$jar" org.springframework.boot.loader.launch.PropertiesLauncher
