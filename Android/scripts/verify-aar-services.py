#!/usr/bin/env python3
"""Verify AutoService descriptors and provider classes in migrated release AARs."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile


KIT_SERVICE = 'META-INF/services/com.didichuxing.doraemonkit.kit.AbstractKit'
ABILITY_SERVICE = 'META-INF/services/com.didichuxing.doraemonkit.kit.core.DokitAbility'
EXPECTED = {
    'dokit': {KIT_SERVICE: 'com.didichuxing.doraemonkit.kit.network.NetworkKit'},
    'dokit-ft': {KIT_SERVICE: 'com.didichuxing.doraemonkit.kit.filemanager.FileTransferKit',
                 ABILITY_SERVICE: 'com.didichuxing.doraemonkit.kit.filemanager.ability.DokitFtAbility'},
    'dokit-mc': {KIT_SERVICE: 'com.didichuxing.doraemonkit.kit.mc.MultiControlKit',
                 ABILITY_SERVICE: 'com.didichuxing.doraemonkit.kit.mc.ability.DoKitMcAbility'},
    'dokit-autotest': {KIT_SERVICE: 'com.didichuxing.doraemonkit.kit.autotest.AutoTestControlKit'},
    'dokit-gps-mock': {KIT_SERVICE: 'com.didichuxing.doraemonkit.gps_mock.gpsmock.GpsMockKit',
                       ABILITY_SERVICE: 'com.didichuxing.doraemonkit.kit.filemanager.ability.DokitGpsMockAbility'},
    'dokit-pthread-hook': {KIT_SERVICE: 'com.kronos.dokit.pthread.PThreadKit'},
    'dokit-weex': {KIT_SERVICE: 'com.didichuxing.doraemonkit.weex.log.WeexLogKit'},
}


def descriptors(archive):
    return {
        name: [line.split('#', 1)[0].strip() for line in archive.read(name).decode('utf-8').splitlines()
               if line.split('#', 1)[0].strip()]
        for name in archive.namelist()
        if name.startswith('META-INF/services/') and not name.endswith('/')
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--modules', nargs='+', choices=list(EXPECTED), default=list(EXPECTED))
    parser.add_argument('--variant', default='release')
    parser.add_argument('--apk', type=Path, help='Also require all AAR providers in an unminified full Demo APK')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    report = {'aars': []}
    all_providers = {}
    for module in args.modules:
        aar = args.root / module / 'build/outputs/aar' / f'{module}-{args.variant}.aar'
        with zipfile.ZipFile(aar) as outer:
            with zipfile.ZipFile(io.BytesIO(outer.read('classes.jar'))) as classes:
                services = descriptors(classes)
                for service, expected_provider in EXPECTED[module].items():
                    assert expected_provider in services.get(service, []), (module, 'missing provider', expected_provider)
                for service, providers in services.items():
                    assert providers == sorted(set(providers)), (module, 'unsorted/duplicate providers', service)
                    for provider in providers:
                        assert provider.replace('.', '/') + '.class' in classes.namelist(), (module, 'missing provider class', provider)
                    all_providers.setdefault(service, set()).update(providers)
        report['aars'].append({'module': module, 'aar': str(aar.resolve()),
                               'sha256': hashlib.sha256(aar.read_bytes()).hexdigest(), 'services': services})
        print(f'{module}: {len(services)} descriptors, {sum(map(len, services.values()))} providers with classes')
    if args.apk:
        with zipfile.ZipFile(args.apk) as apk:
            merged = descriptors(apk)
        for service, providers in all_providers.items():
            assert providers <= set(merged.get(service, [])), (str(args.apk), service, 'AAR providers lost during APK packaging')
        report['apk'] = {'path': str(args.apk.resolve()), 'services': merged}
        print(f'{args.apk.name}: all AAR service providers retained')
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
