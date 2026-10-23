#!/usr/bin/env bash
set -euo pipefail
plugin_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec "${plugin_dir}/../gradlew" -p "${plugin_dir}" publishAllPublicationsToMigrationRepository "$@"
