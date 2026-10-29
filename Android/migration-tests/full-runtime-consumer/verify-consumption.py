#!/usr/bin/env python3
"""Compare published AutoService providers with standalone consumer APKs."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zipfile


GROUP = 'io.github.qqnp1100'
VERSION = '3.7.15'
ARTIFACTS = ('dokitx', 'dokitx-ft', 'dokitx-mc', 'dokitx-autotest',
             'dokitx-gps-mock', 'dokitx-pthread-hook')
SERVICES = ('com.didichuxing.doraemonkit.kit.AbstractKit',
            'com.didichuxing.doraemonkit.kit.core.DokitAbility')


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def descriptors(archive):
    result = {}
    for service in SERVICES:
        name = 'META-INF/services/' + service
        if name in archive.namelist():
            result[service] = [line.split('#', 1)[0].strip()
                               for line in archive.read(name).decode('utf-8').splitlines()
                               if line.split('#', 1)[0].strip()]
    return result


def published_file(repository, artifact, version, extension, group=GROUP):
    directory = repository / group.replace('.', '/') / artifact / version
    published_version = version
    if version.endswith('-SNAPSHOT'):
        metadata = ET.parse(directory / 'maven-metadata.xml')
        versions = [entry.findtext('value')
                    for entry in metadata.findall('./versioning/snapshotVersions/snapshotVersion')
                    if entry.findtext('extension') == extension and entry.find('classifier') is None]
        assert len(versions) == 1, (artifact, extension, 'missing/ambiguous snapshot version')
        published_version = versions[0]
    return directory / f'{artifact}-{published_version}.{extension}'


def dex_defined_classes(data):
    """Read standard DEX class_defs through type_ids/string_ids."""
    assert data.startswith(b'dex\n'), 'Expected a standard APK DEX file'
    u32 = lambda offset: struct.unpack_from('<I', data, offset)[0]
    strings_offset, types_offset = u32(60), u32(68)
    classes_size, classes_offset = u32(96), u32(100)
    classes = set()
    for index in range(classes_size):
        type_index = u32(classes_offset + index * 32)
        string_index = u32(types_offset + type_index * 4)
        offset = u32(strings_offset + string_index * 4)
        # string_data_item begins with a ULEB128 UTF-16 length.
        while data[offset] & 0x80:
            offset += 1
        offset += 1
        classes.add(data[offset:data.index(b'\0', offset)].decode('utf-8'))
    return classes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True, type=Path)
    parser.add_argument('--group', default=GROUP)
    parser.add_argument('--version', default=VERSION)
    parser.add_argument('--metadata', choices=['module', 'pom'], required=True)
    parser.add_argument('--fixture', type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    all_providers = {service: set() for service in SERVICES}
    report = {'metadata': args.metadata, 'publishedAars': [], 'apks': [],
              'runtimeAssertionsExecuted': False, 'nativeFeaturesExecuted': False}
    for artifact in ARTIFACTS:
        aar = published_file(args.repository, artifact, args.version, 'aar', args.group)
        with zipfile.ZipFile(aar) as outer:
            rules = outer.read('proguard.txt').decode('utf-8')
            with zipfile.ZipFile(io.BytesIO(outer.read('classes.jar'))) as classes:
                services = descriptors(classes)
                for service, providers in services.items():
                    assert providers == sorted(set(providers)), (artifact, 'provider ordering/duplicates')
                    for provider in providers:
                        assert provider.replace('.', '/') + '.class' in classes.namelist(), (artifact, provider)
                        rule = f'-keep,allowoptimization class {provider} {{ public <init>(); }}'
                        assert rule in rules, (artifact, 'missing precise published constructor rule', provider)
                    all_providers[service].update(providers)
        report['publishedAars'].append({'artifact': artifact, 'path': str(aar.resolve()),
                                        'sha256': sha256(aar), 'services': services})
    assert all(all_providers.values()), 'Both DoKit service interfaces must have providers'
    for variant in ('debug', 'release'):
        apk = args.fixture / f'build/outputs/apk/{variant}/dokit-full-runtime-consumer-{variant}.apk'
        with zipfile.ZipFile(apk) as archive:
            services = descriptors(archive)
            defined = set()
            for name in archive.namelist():
                if name.startswith('classes') and name.endswith('.dex'):
                    defined.update(dex_defined_classes(archive.read(name)))
            for service, providers in all_providers.items():
                assert providers == set(services.get(service, [])), (variant, service, 'service resource differs')
                for provider in providers:
                    descriptor = 'L' + provider.replace('.', '/') + ';'
                    assert descriptor in defined, (variant, 'provider class removed/renamed', provider)
            assert ('Lcom/didichuxing/dokit/migration/fullconsumer/ConsumerActivity;' in defined), variant
        report['apks'].append({'variant': variant, 'path': str(apk.resolve()), 'sha256': sha256(apk),
                               'definedClasses': len(defined), 'services': services})
        print(f'{args.metadata} {variant}: {sum(map(len, all_providers.values()))} published providers '
              f'retained in service resources and DEX ({len(defined)} classes)')
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
