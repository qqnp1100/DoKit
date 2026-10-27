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
publish_prepare_only=false
if [[ "$publish_target" == central ]]; then
  if [[ "${1:-}" == --prepare ]]; then
    publish_prepare_only=true
    shift
  fi
  publish_check=(python3 scripts/central-publishing.py check)
  if [[ "$publish_prepare_only" == false ]]; then
    publish_check+=(--require-token)
  fi
  "${publish_check[@]}"
  python3 scripts/central-publishing.py clean
  # Legacy marker namespaces belong to upstream. Only the new namespaced
  # marker and the implementation JAR are included in the Central deployment.
  "${publish_gradle[@]}" -p dokit-plugin \
    publishPluginMavenPublicationToCentralRepository \
    publishCentralPluginMarkerMavenPublicationToCentralRepository \
    -PDOKIT_PUBLISH_TARGET=central "$@"
else
  "${publish_gradle[@]}" -p dokit-plugin publishAllPublicationsToMigrationRepository "$@"
fi
publish_tasks=()
for publish_module in dokit dokit-no-op dokit-okhttp-api dokit-okhttp-v3 dokit-okhttp-v4 dokit-ft dokit-test dokit-autotest dokit-mc dokit-util dokit-weex dokit-pthread-hook dokit-gps-mock; do
  publish_tasks+=(":$publish_module:publishReleasePublicationTo${publish_repository}Repository")
done
"${publish_gradle[@]}" "${publish_tasks[@]}" "-PDOKIT_PUBLISH_TARGET=$publish_target" "$@"
if [[ "$publish_target" == central ]]; then
  python3 scripts/central-publishing.py bundle
  if [[ "$publish_prepare_only" == false ]]; then
    python3 scripts/central-publishing.py upload
  fi
elif [[ "$publish_target" == private ]]; then
  printf 'Android libraries published; plugin and markers remain in dokit-plugin/build/migration-repository for explicit distribution.\n'
fi
