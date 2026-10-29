#!/usr/bin/env python3
"""Check generated Android release POM and Gradle Module Metadata contracts."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--group', default='io.github.qqnp1100')
    parser.add_argument('--version', default='3.7.15')
    parser.add_argument('--modules', nargs='+', default=['dokit', 'dokit-weex', 'dokit-okhttp-v3', 'dokit-okhttp-v4'])
    args = parser.parse_args()
    mapping = json.loads((args.root / 'dokit_module.json').read_text())
    expected = {
        'dokit': {('com.google.code.gson', 'gson'): 'compile',
                  (args.group, mapping['dokit-util']): 'runtime',
                  (args.group, mapping['dokit-okhttp-api']): 'runtime'},
        'dokit-weex': {(args.group, mapping['dokit']): 'compile'},
        'dokit-okhttp-v3': {('com.squareup.okhttp3', 'okhttp'): 'compile'},
        'dokit-okhttp-v4': {},
        'dokit-util': {},
        'dokit-no-op': {('androidx.fragment', 'fragment'): 'compile',
                        ('androidx.annotation', 'annotation'): 'compile'},
    }
    forbidden = {
        'dokit': {('io.coil-kt', 'coil'), ('com.github.bumptech.glide', 'glide')},
        'dokit-weex': {('org.apache.weex', 'sdk')},
        'dokit-okhttp-v4': {('com.squareup.okhttp3', 'okhttp')},
        'dokit-no-op': {('io.github.didi.hummer', 'hummer-sdk'), ('com.squareup.okhttp3', 'okhttp')},
    }
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    for module in args.modules:
        dependencies = expected[module]
        directory = args.root / module / 'build/publications/release'
        pom = ET.parse(directory / 'pom-default.xml').getroot()
        def value(element, key):
            return element.findtext(f'm:{key}', namespaces=ns)
        assert value(pom, 'groupId') == args.group, module
        assert value(pom, 'artifactId') == mapping[module], module
        assert value(pom, 'version') == args.version, module
        published = {}
        for dependency in pom.findall('m:dependencies/m:dependency', ns):
            key = (value(dependency, 'groupId'), value(dependency, 'artifactId'))
            assert key not in published, (module, 'duplicate dependency', key)
            published[key] = value(dependency, 'scope') or 'compile'
            if key[0] == args.group:
                assert key[1] in mapping.values(), (module, 'unmapped project dependency', key)
                assert value(dependency, 'version') == args.version, (module, key)
        for key, scope in dependencies.items():
            assert published.get(key) == scope, (module, key, published.get(key), scope)
        assert not (forbidden.get(module, set()) & published.keys()), (module, 'compileOnly dependency leaked')
        metadata = json.loads((directory / 'module.json').read_text())
        for key, expected_value in {'group': args.group, 'module': mapping[module], 'version': args.version}.items():
            assert metadata['component'][key] == expected_value, (module, metadata['component'])
        variants = metadata['variants']
        usages = {variant['attributes'].get('org.gradle.usage') for variant in variants}
        assert {'java-api', 'java-runtime'} <= usages, (module, usages)
        assert any(variant['attributes'].get('org.gradle.docstype') == 'sources' for variant in variants), (module, 'missing sources variant')
        for variant in variants:
            for dependency in variant.get('dependencies', []):
                if dependency['group'] == args.group:
                    assert dependency['module'] in mapping.values(), (module, dependency)
                    assert dependency['version']['requires'] == args.version, (module, dependency)
        print(f'{module}: publication coordinates, dependency scopes and module variants verified')


if __name__ == '__main__':
    main()
