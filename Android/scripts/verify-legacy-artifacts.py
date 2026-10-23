#!/usr/bin/env python3
"""Compare cached legacy artifacts with the exact migration repository evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=Path(__file__).resolve().parents[1] / 'gradle/legacy-artifacts.json')
    parser.add_argument('--cache', type=Path, default=Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1')
    parser.add_argument('--artifact-directory', type=Path, help='Check a flat directory containing group-module-version.pom/aar instead of the Gradle cache')
    parser.add_argument('--require-all', action='store_true', help='Fail if any recorded binary has not been downloaded; cached POMs are checked when available')
    parser.add_argument('--require-poms', action='store_true', help='Also require raw POM files, which Gradle may evict after parsing metadata')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    checked = []
    failures = []
    missing = []
    for artifact in manifest['artifacts']:
        group, module, version = (artifact[key] for key in ('group', 'module', 'version'))
        coordinate = f'{group}:{module}:{version}'
        cache_directory = args.cache / group / module / version
        for extension, expected_hash in [('pom', artifact['pomSha256']),
                                         (artifact['artifactExtension'], artifact['artifactSha256'])]:
            if args.artifact_directory:
                candidate = args.artifact_directory / f'{group}-{module}-{version}.{extension}'
                candidates = [candidate] if candidate.is_file() else []
            else:
                candidates = sorted(cache_directory.glob(f'*/{module}-{version}.{extension}'))
            if not candidates:
                missing.append(f'{coordinate}.{extension}')
            for candidate in candidates:
                actual_hash = hashlib.sha256(candidate.read_bytes()).hexdigest()
                result = {'coordinate': coordinate, 'extension': extension, 'path': str(candidate),
                          'sha256': actual_hash, 'matches': actual_hash == expected_hash}
                checked.append(result)
                if not result['matches']:
                    failures.append(result)
    report = {'repository': manifest['repository'], 'checkedFiles': len(checked),
              'missing': missing, 'mismatches': failures, 'artifacts': checked}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + '\n')
    for failure in failures:
        print(f"MISMATCH: {failure['path']} ({failure['sha256']})")
    for item in missing:
        print(f'NOT DOWNLOADED: {item}')
    print(f'Verified {len(checked) - len(failures)}/{len(checked)} cached POM/binary files; {len(missing)} missing')
    missing_binaries = [item for item in missing if not item.endswith('.pom')]
    missing_poms = [item for item in missing if item.endswith('.pom')]
    return 1 if failures or (args.require_all and missing_binaries) or (args.require_poms and missing_poms) else 0


if __name__ == '__main__':
    raise SystemExit(main())
