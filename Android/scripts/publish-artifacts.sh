#!/usr/bin/env bash
set -euo pipefail
migration_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$migration_root"
publish_target="${1:-local}"
shift || true
if command -v rtk >/dev/null 2>&1; then
  publish_gradle=(rtk proxy ./gradlew)
else
  publish_gradle=(./gradlew)
fi
case "$publish_target" in
  local) publish_repository=Migration ;;
  private) publish_repository=Private ;;
  central) publish_repository=Central ;;
  *) printf 'Unknown publication target: %s\n' "$publish_target" >&2; exit 2 ;;
esac
# Publish plugin/markers locally first. Its independent build has no configured
# remote destination; remote plugin distribution is a separate explicit step.
"${publish_gradle[@]}" -p dokit-plugin publishAllPublicationsToMigrationRepository "$@"
publish_tasks=()
for publish_module in dokit dokit-no-op dokit-okhttp-api dokit-okhttp-v3 dokit-okhttp-v4 dokit-ft dokit-test dokit-autotest dokit-mc dokit-util dokit-weex dokit-pthread-hook dokit-gps-mock; do
  publish_tasks+=(":$publish_module:publishReleasePublicationTo${publish_repository}Repository")
done
"${publish_gradle[@]}" "${publish_tasks[@]}" "-PDOKIT_PUBLISH_TARGET=$publish_target" "$@"
if [[ "$publish_target" != local ]]; then
  printf 'Android libraries published; plugin and markers remain in dokit-plugin/build/migration-repository for explicit distribution.\n'
fi
