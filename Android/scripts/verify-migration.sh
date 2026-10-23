#!/usr/bin/env bash
set -euo pipefail

migration_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$migration_root"
migration_version="$(sed -n 's/^dokitPublishingVersion=//p' dokit-plugin/gradle.properties)"
migration_group="$(sed -n 's/^dokitPublishingGroup=//p' dokit-plugin/gradle.properties)"
: "${migration_version:?Missing dokitPublishingVersion}"
: "${migration_group:?Missing dokitPublishingGroup}"
migration_phase="${MIGRATION_PHASE:-modules}"
case "$migration_phase" in
  configuration|modules|instrumentation|cache) ;;
  *) printf 'Unknown MIGRATION_PHASE: %s\n' "$migration_phase" >&2; exit 2 ;;
esac
migration_reports="$migration_root/build/migration-reports"
mkdir -p "$migration_reports"
# A standalone consumer has its own project root, so forward the SDK location
# from this checkout when the caller relies on local.properties.
if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" && -f local.properties ]]; then
  migration_sdk="$(python3 - <<'PY'
from pathlib import Path
import re
for line in Path('local.properties').read_text().splitlines():
    match = re.match(r'\s*sdk\.dir\s*[:=]\s*(.*)', line)
    if match:
        print(re.sub(r'\\(.)', r'\1', match.group(1).strip()))
        break
PY
)"
  if [[ -n "$migration_sdk" ]]; then
    export ANDROID_HOME="$migration_sdk"
  fi
fi
if command -v rtk >/dev/null 2>&1; then
  migration_gradle=(rtk proxy ./gradlew)
else
  migration_gradle=(./gradlew)
fi
migration_flags=(--console=plain --warning-mode=all --stacktrace "--max-workers=${MIGRATION_MAX_WORKERS:-2}")
if [[ "${MIGRATION_OFFLINE:-false}" == true ]]; then
  migration_flags+=(--offline)
fi
run_gradle() {
  local migration_log="$1"
  shift
  "${migration_gradle[@]}" "$@" "${migration_flags[@]}" 2>&1 | tee "$migration_reports/$migration_log.log"
}

python3 scripts/native-inventory.py
run_gradle versions --version
run_gradle configuration help projects
run_gradle build-environment buildEnvironment
python3 - "$migration_reports" <<'PY'
from pathlib import Path
import sys
reports = Path(sys.argv[1])
assert 'Gradle 9.8.2' in (reports / 'versions.log').read_text(), 'Wrapper must use Gradle 9.8.2'
environment = (reports / 'build-environment.log').read_text()
for coordinate in ('com.android.tools.build:gradle:9.4.1',
                   'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21'):
    assert coordinate in environment, f'Missing target build dependency: {coordinate}'
PY
run_gradle runtime-recorders -p migration-tests/runtime-recorders test
run_gradle plugin -p dokit-plugin test validatePlugins -Pdokit.androidSdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ "$migration_phase" == configuration ]]; then
  exit 0
fi

run_gradle libraries \
  :dokit-util:assembleRelease :dokit-no-op:assembleRelease \
  :dokit-okhttp-api:assembleRelease :dokit:assembleRelease \
  :dokit-ft:assembleRelease :dokit-weex:assembleRelease \
  :dokit-test:assembleRelease :dokit-mc:assembleRelease \
  :dokit-autotest:assembleRelease :dokit-gps-mock:assembleRelease \
  :dokit-pthread-hook:assembleRelease
run_gradle demo-without-instrumentation :app:assembleDebug :app:assembleRelease -PDOKIT_PLUGIN_SWITCH=false
python3 scripts/verify-aar-services.py --report "$migration_reports/aar-services.json"
python3 scripts/verify-aar-services.py \
  --modules dokit dokit-ft dokit-mc dokit-autotest dokit-gps-mock dokit-pthread-hook \
  --apk app/build/outputs/apk/debug/app-debug.apk --report "$migration_reports/apk-services.json"
python3 scripts/verify-native-cpp-runtime.py app/build/outputs/apk/debug/app-debug.apk \
  --expected-sha256 59aa4cc063b05735c785a70c3ce3598a4e600f9521e8c479151b8d673e7156bf \
  --only-abi --output "$migration_reports/native-cpp-runtime.json"
python3 scripts/verify-legacy-artifacts.py --require-all --report "$migration_reports/legacy-artifacts.json"
run_gradle debug-dependencies :app:dependencies --configuration debugRuntimeClasspath
run_gradle release-dependencies :app:dependencies --configuration releaseRuntimeClasspath
python3 - "$migration_reports/release-dependencies.log" <<'PY'
from pathlib import Path
import re
import sys
dependencies = Path(sys.argv[1]).read_text()
assert re.search(r'''(?:project ['"]?:dokit-no-op\b|com\.didichuxing\.doraemonkit:dokitx-no-op:)''', dependencies), 'Release must resolve no-op'
for project, artifact in [('dokit', 'dokitx'), ('dokit-mc', 'dokitx-mc'),
                          ('dokit-autotest', 'dokitx-autotest'), ('dokit-test', 'dokitx-test'),
                          ('dokit-pthread-hook', 'dokitx-pthread-hook')]:
    assert not re.search(r'''project ['"]?:''' + re.escape(project) + r'''(?:['"]|\s|$)''', dependencies), f'Full debug project leaked: {project}'
    assert f'com.didichuxing.doraemonkit:{artifact}:' not in dependencies, f'Full debug artifact leaked: {artifact}'
PY
run_gradle compiler :dokit:dependencyInsight --configuration kotlinCompilerClasspath --dependency kotlin-compiler-embeddable
python3 - "$migration_reports/compiler.log" <<'PY'
from pathlib import Path
import sys
assert 'org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21' in Path(sys.argv[1]).read_text(), 'Android compiler must resolve to Kotlin 2.4.21'
PY
run_gradle metadata \
  :dokit-no-op:generatePomFileForReleasePublication :dokit-no-op:generateMetadataFileForReleasePublication \
  :dokit-util:generatePomFileForReleasePublication :dokit-util:generateMetadataFileForReleasePublication \
  :dokit:generatePomFileForReleasePublication :dokit:generateMetadataFileForReleasePublication \
  :dokit-weex:generatePomFileForReleasePublication :dokit-weex:generateMetadataFileForReleasePublication \
  :dokit-okhttp-v3:generatePomFileForReleasePublication :dokit-okhttp-v3:generateMetadataFileForReleasePublication \
  :dokit-okhttp-v4:generatePomFileForReleasePublication :dokit-okhttp-v4:generateMetadataFileForReleasePublication
python3 scripts/verify-publication-metadata.py --group "$migration_group" --version "$migration_version" --modules dokit dokit-weex dokit-okhttp-v3 dokit-okhttp-v4 dokit-no-op dokit-util
run_gradle local-publication \
  :dokit-util:publishReleasePublicationToMigrationRepository \
  :dokit-no-op:publishReleasePublicationToMigrationRepository \
  :dokit-okhttp-api:publishReleasePublicationToMigrationRepository \
  :dokit-okhttp-v3:publishReleasePublicationToMigrationRepository \
  :dokit-okhttp-v4:publishReleasePublicationToMigrationRepository \
  :dokit:publishReleasePublicationToMigrationRepository \
  :dokit-ft:publishReleasePublicationToMigrationRepository \
  :dokit-weex:publishReleasePublicationToMigrationRepository \
  :dokit-test:publishReleasePublicationToMigrationRepository \
  :dokit-mc:publishReleasePublicationToMigrationRepository \
  :dokit-autotest:publishReleasePublicationToMigrationRepository \
  :dokit-gps-mock:publishReleasePublicationToMigrationRepository \
  :dokit-pthread-hook:publishReleasePublicationToMigrationRepository --max-workers=2
run_gradle maven-consumer -p migration-tests/maven-consumer \
  -PmigrationRepository="$migration_root/build/migration-repository" \
  -PmigrationVersion="$migration_version" assembleRelease
run_gradle maven-consumer-dependencies -p migration-tests/maven-consumer \
  -PmigrationRepository="$migration_root/build/migration-repository" \
  -PmigrationVersion="$migration_version" dependencies --configuration releaseRuntimeClasspath
python3 - "$migration_reports/maven-consumer-dependencies.log" <<'PY'
from pathlib import Path
import re
import sys
dependencies = Path(sys.argv[1]).read_text()
assert 'com.didichuxing.doraemonkit:dokitx-no-op:' in dependencies, 'Published no-op must be resolved'
assert 'com.didichuxing.doraemonkit:dokitx-util:' in dependencies, 'Published util must be resolved'
for module in ('dokitx', 'dokitx-mc', 'dokitx-autotest', 'dokitx-test', 'dokitx-pthread-hook'):
    assert not re.search(r'com\.didichuxing\.doraemonkit:' + module + r':', dependencies), f'Full debug implementation leaked: {module}'
assert not re.search(r'''\bproject ['"]?:dokit''', dependencies), 'Consumer must resolve Maven artifacts'
PY
for migration_metadata in module pom; do
  run_gradle "full-maven-consumer-$migration_metadata" -p migration-tests/full-runtime-consumer \
    -PmigrationRepository="$migration_root/build/migration-repository" \
    -PmigrationVersion="$migration_version" -PmigrationMetadata="$migration_metadata" \
    assembleDebug assembleRelease --refresh-dependencies --max-workers=2
  # Inspect variants in a separate invocation: --all-variants must run before
  # assembly or a dependency-report task resolves that configuration.
  run_gradle "full-maven-insight-$migration_metadata" -p migration-tests/full-runtime-consumer \
    -PmigrationRepository="$migration_root/build/migration-repository" \
    -PmigrationVersion="$migration_version" -PmigrationMetadata="$migration_metadata" \
    dependencyInsight --dependency dokitx --configuration releaseRuntimeClasspath --all-variants --max-workers=2
  python3 migration-tests/full-runtime-consumer/verify-consumption.py \
    --repository "$migration_root/build/migration-repository" --version "$migration_version" \
    --metadata "$migration_metadata" --report "$migration_reports/full-maven-$migration_metadata.json"
done
python3 - "$migration_reports" <<'PY'
from pathlib import Path
import re
import sys
reports = Path(sys.argv[1])
for metadata, variant in [('module', 'releaseVariantReleaseRuntimePublication'), ('pom', 'runtime')]:
    insight = (reports / f'full-maven-insight-{metadata}.log').read_text()
    assert f'Variant {variant}:' in insight, f'{metadata} metadata was not selected'
    for artifact in ('dokitx', 'dokitx-ft', 'dokitx-mc', 'dokitx-autotest', 'dokitx-gps-mock', 'dokitx-pthread-hook'):
        assert f'com.didichuxing.doraemonkit:{artifact}:' in insight, f'Missing published artifact: {artifact}'
    assert not re.search(r'''\bproject ['"]?:dokit''', insight), 'Full consumer must resolve Maven artifacts'
    assert 'com.didichuxing.doraemonkit:dokitx-no-op:' not in insight, 'Full consumer must resolve full runtime'
PY
if [[ "$migration_phase" == modules ]]; then
  exit 0
fi

run_gradle instrumentation-basic :app:assembleDebug -PDOKIT_PLUGIN_SWITCH=true -PDOKIT_METHOD_SWITCH=false
cp app/build/reports/dokit/debug/instrumentation.txt "$migration_reports/instrumentation-basic.txt"
run_gradle instrumentation-method :app:assembleDebug -PDOKIT_PLUGIN_SWITCH=true -PDOKIT_METHOD_SWITCH=true -PDOKIT_METHOD_STRATEGY=1
cp app/build/reports/dokit/debug/instrumentation.txt "$migration_reports/instrumentation-method.txt"
run_gradle instrumentation-stack :app:assembleDebug -PDOKIT_PLUGIN_SWITCH=true -PDOKIT_METHOD_SWITCH=true -PDOKIT_METHOD_STRATEGY=0
cp app/build/reports/dokit/debug/instrumentation.txt "$migration_reports/instrumentation-stack.txt"
run_gradle variants :app:assembleDebug :app:assembleRelease --parallel -PDOKIT_PLUGIN_SWITCH=true
run_gradle unit-tests :app:testDebugUnitTest :dokit:testDebugUnitTest :dokit-ft:testDebugUnitTest
run_gradle lint :app:lintDebug :dokit:lintDebug
# Existing modules keep abortOnError=false. Preserve the actual findings so a
# successful Gradle task cannot be mistaken for a clean lint report.
for migration_module in app dokit; do
  for migration_format in xml html; do
    cp "$migration_module/build/reports/lint-results-debug.$migration_format" \
      "$migration_reports/$migration_module-lint-results-debug.$migration_format"
  done
done
if [[ "$migration_phase" == cache ]]; then
  for migration_attempt in 1 2; do
    run_gradle "cache-$migration_attempt" :app:assembleDebug --configuration-cache --configuration-cache-problems=fail --build-cache
  done
fi
