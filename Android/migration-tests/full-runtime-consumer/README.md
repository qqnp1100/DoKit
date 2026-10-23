# Published full-runtime consumer

This is an independent Android host, with no included build, project dependency,
or dependency substitution. It consumes six published DoKit runtime libraries
and their transitive dependencies from a file Maven repository. The compiler
versions are Kotlin 2.4.21, Gradle 9.8.2 and AGP 9.4.1; compileSdk is 34,
targetSdk is 31, and minSdk is 21.

Run from `Android/` after publishing the release components to
`build/migration-repository`:

```sh
./gradlew -p migration-tests/full-runtime-consumer \
  -PmigrationRepository="$PWD/build/migration-repository" \
  -PmigrationMetadata=module assembleDebug assembleRelease --max-workers=2
python3 migration-tests/full-runtime-consumer/verify-consumption.py \
  --repository build/migration-repository --metadata module
```

Repeat with `-PmigrationMetadata=pom` and verifier `--metadata pom` to force POM
consumption, including disabling Gradle metadata redirection. Run
`dependencyInsight --dependency dokitx --configuration releaseRuntimeClasspath
--all-variants` separately from assembly to inspect the selected metadata
variants without an already-resolved configuration.

Release is minified with R8 and debug-signed for this private fixture. Its only
local keep rule preserves the assertion Activity. DoKit service constructor
rules and precise optional-dependency warnings come from the published AARs.
The verifier reads timestamped SNAPSHOT metadata and compares every published
DoKit service provider with both APK service resources and defined DEX classes.

The launcher uses the public `DoKit.Builder` and enumerates `ServiceLoader`
providers. It deliberately does not call `Builder.build()` or service
`onAppInit`, so a device launch would exercise discovery without initializing
all native features. Building and inspecting the APKs does not execute those
runtime assertions or validate native features. No device install is part of
this fixture's build.
