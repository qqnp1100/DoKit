#!/usr/bin/env python3
"""Validate, bundle and upload DoKit releases using the Central Publisher API."""
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
STAGING = ROOT / 'build/central-staging-repository'
PORTAL = 'https://central.sonatype.com'
POM_NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def read_properties(path):
    """Read Java properties, including escaped passwords and multiline keys."""
    result, pending = {}, ''
    if not path.is_file():
        return result
    for line in path.read_text(encoding='utf-8').splitlines():
        line = pending + line.lstrip()
        if (len(line) - len(line.rstrip('\\'))) % 2:
            pending = line[:-1]
            continue
        pending = ''
        if not line or line.startswith(('#', '!')):
            continue
        match = re.match(r'([^\s:=]+)\s*[:=]?\s*(.*)', line)
        if match:
            value = re.sub(r'\\(u[0-9a-fA-F]{4}|.)', lambda m: (
                chr(int(m[1][1:], 16)) if m[1].startswith('u') else
                {'n': '\n', 'r': '\r', 't': '\t', 'f': '\f'}.get(m[1], m[1])
            ), match[2])
            result[match[1]] = value.strip()
    return result


def settings():
    values = read_properties(ROOT / 'local.properties')
    config = ROOT / os.environ.get('DOKIT_PUBLISH_PROPERTIES_FILE', 'central-publishing.properties')
    values.update(read_properties(config))
    values.update({key: value.strip() for key, value in os.environ.items()
                   if key.startswith(('DOKIT_', 'MAVEN_CENTRAL_'))})
    return values


def coordinates():
    values = read_properties(ROOT / 'dokit-plugin/gradle.properties')
    group, version = values['dokitPublishingGroup'], values['dokitPublishingVersion']
    if not re.fullmatch(r'[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)+', group):
        raise ValueError('Invalid publishing groupId')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+', version) or version.endswith('-SNAPSHOT'):
        raise ValueError('Central releases require a version without -SNAPSHOT')
    return group, version


def artifacts(group):
    mapping = json.loads((ROOT / 'dokit_module.json').read_text())
    modules = re.findall(r"^include\s+['\"]:([^'\"]+)['\"]", (ROOT / 'settings.gradle').read_text(), re.M)
    result = {(group, mapping[module]): 'aar' for module in modules if module != 'app'}
    result[group, mapping['dokit-plugin']] = 'jar'
    plugin_id = group + '.dokit'
    result[plugin_id, plugin_id + '.gradle.plugin'] = 'pom'
    return result


def check_configuration(require_token=False):
    group, version = coordinates()
    values = settings()
    key = values.get('DOKIT_SIGNING_KEY', '')
    key_file = values.get('DOKIT_SIGNING_KEY_FILE')
    if not key and key_file:
        key = (ROOT / key_file).read_text(encoding='utf-8')
    legacy_key = values.get('signing.secretKeyRingFile')
    if not key and not (legacy_key and Path(legacy_key).is_file() and values.get('signing.keyId')):
        raise ValueError('Set DOKIT_SIGNING_KEY_FILE and DOKIT_SIGNING_PASSWORD in central-publishing.properties before preparing a signed release.')
    if key and '-----BEGIN PGP PRIVATE KEY BLOCK-----' not in key:
        raise ValueError('The signing key must be an ASCII-armored GPG private key.')
    if require_token and not token_credentials(values)[0]:
        raise ValueError('Set both DOKIT_PUBLISH_USERNAME and DOKIT_PUBLISH_PASSWORD to your Central Portal user token.')
    print(f'Publishing configuration ready: {group}, version {version}; signing key present.')


def token_credentials(values):
    username = values.get('DOKIT_PUBLISH_USERNAME') or values.get('MAVEN_CENTRAL_USERNAME', '')
    password = values.get('DOKIT_PUBLISH_PASSWORD') or values.get('MAVEN_CENTRAL_PASSWORD', '')
    return (username if password else ''), password


def validate_files(files, group, version):
    expected = artifacts(group)
    directories = {f'{g.replace(".", "/")}/{a}/{version}': (g, a, packaging)
                   for (g, a), packaging in expected.items()}
    expected_poms = {f'{directory}/{artifact}-{version}.pom'
                     for directory, (_, artifact, _) in directories.items()}
    if {name for name in files if name.endswith('.pom')} != expected_poms:
        raise ValueError('Bundle must contain exactly the enabled SDK modules, plugin JAR and namespaced plugin marker at the configured version.')
    if any(str(Path(name).parent) not in directories for name in files):
        raise ValueError('Bundle contains files outside the expected Maven coordinates.')
    internal_artifacts = {artifact for g, artifact in expected if g == group}
    for directory, (expected_group, artifact, packaging) in directories.items():
        prefix = f'{directory}/{artifact}-{version}'
        pom = ET.fromstring(files[prefix + '.pom'])
        def value(key):
            return pom.findtext('m:' + key, namespaces=POM_NS)
        if (value('groupId'), value('artifactId'), value('version')) != (expected_group, artifact, version):
            raise ValueError(f'Incorrect POM coordinates: {prefix}')
        for key in ['name', 'description', 'url', 'licenses/m:license/m:name',
                    'licenses/m:license/m:url', 'developers/m:developer/m:name',
                    'developers/m:developer/m:email', 'scm/m:connection', 'scm/m:url']:
            if not value(key):
                raise ValueError(f'Missing POM metadata {key}: {prefix}')
        for dependency in pom.findall('m:dependencies/m:dependency', POM_NS):
            dep = {key: dependency.findtext('m:' + key, namespaces=POM_NS)
                   for key in ['groupId', 'artifactId', 'version']}
            if dep['artifactId'] in internal_artifacts and (dep['groupId'], dep['version']) != (group, version):
                raise ValueError(f'Incorrect internal dependency in {prefix}')
        required = [prefix + '.pom']
        if packaging != 'pom':
            required += [prefix + '.' + packaging, prefix + '-sources.jar', prefix + '-javadoc.jar']
        else:
            dependency = pom.find('m:dependencies/m:dependency', POM_NS)
            if dependency is None or dependency.findtext('m:artifactId', namespaces=POM_NS) != 'dokitx-plugin':
                raise ValueError('The plugin marker must depend on dokitx-plugin.')
        for name in required:
            if not files.get(name):
                raise ValueError(f'Missing publication artifact: {name}')
        module = prefix + '.module'
        if module in files:
            metadata = json.loads(files[module])
            component = metadata['component']
            if (component['group'], component['module'], component['version']) != (group, artifact, version):
                raise ValueError(f'Incorrect Gradle Module Metadata coordinates: {module}')
            for variant in metadata['variants']:
                for dep in variant.get('dependencies', []):
                    if dep['module'] in internal_artifacts and (dep['group'], dep['version'].get('requires')) != (group, version):
                        raise ValueError(f'Incorrect internal Gradle dependency in {module}')
    for name, data in files.items():
        if name.endswith(('.pom', '.aar', '.jar', '.module')):
            if not files.get(name + '.asc', b'').startswith(b'-----BEGIN PGP SIGNATURE-----'):
                raise ValueError(f'Missing PGP signature: {name}.asc')
            for algorithm in ['md5', 'sha1']:
                expected_hash = hashlib.new(algorithm, data).hexdigest().encode()
                if files.get(name + '.' + algorithm, b'').strip() != expected_hash:
                    raise ValueError(f'Missing or incorrect {algorithm} checksum: {name}')


def make_bundle(repository, output):
    group, version = coordinates()
    files = {}
    for path in sorted(repository.rglob('*')):
        if path.is_file() and path.suffix in ['.pom', '.aar', '.jar', '.module', '.asc']:
            name = path.relative_to(repository).as_posix()
            files[name] = path.read_bytes()
            if path.suffix != '.asc':
                for algorithm in ['md5', 'sha1']:
                    files[name + '.' + algorithm] = hashlib.new(algorithm, files[name]).hexdigest().encode()
    validate_files(files, group, version)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix('.zip.tmp')
    with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as bundle:
        for name, data in sorted(files.items()):
            bundle.writestr(name, data)
    temporary.replace(output)
    print(f'Central bundle ready: {output} ({len(artifacts(group))} publications, {output.stat().st_size} bytes)')


def upload_bundle(bundle_path):
    group, version = coordinates()
    payload = bundle_path.read_bytes()
    with zipfile.ZipFile(io.BytesIO(payload)) as bundle:
        names = bundle.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Bundle contains duplicate entries.')
        validate_files({name: bundle.read(name) for name in names}, group, version)
    username, password = token_credentials(settings())
    if not username:
        raise ValueError('Central Portal user token username and password are required.')
    token = base64.b64encode(f'{username}:{password}'.encode()).decode()
    boundary = 'dokit-' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="central-bundle.zip"\r\n'
            'Content-Type: application/octet-stream\r\n\r\n').encode() + payload + f'\r\n--{boundary}--\r\n'.encode()
    query = urllib.parse.urlencode({'name': f'DoKit {version}', 'publishingType': 'USER_MANAGED'})
    request = urllib.request.Request(PORTAL + '/api/v1/publisher/upload?' + query, data=body, headers={
        'Authorization': 'Bearer ' + token,
        'Content-Type': 'multipart/form-data; boundary=' + boundary,
    }, method='POST')
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            deployment_id = str(uuid.UUID(response.read().decode().strip()))
    except urllib.error.HTTPError as error:
        raise ValueError(f'Central Portal upload returned HTTP {error.code}. Check token and namespace permissions in the Portal.') from None
    except (urllib.error.URLError, TimeoutError):
        raise ValueError('Upload connection failed. Check Portal Deployments before retrying; the server may already have accepted the bundle.') from None
    bundle_path.with_suffix('.deployment-id').write_text(deployment_id + '\n')
    print(f'Uploaded deployment {deployment_id}. Central validation is pending; publishingType=USER_MANAGED.')
    print(PORTAL + '/publishing/deployments')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['check', 'clean', 'bundle', 'upload'])
    parser.add_argument('--require-token', action='store_true')
    parser.add_argument('--repository', type=Path, default=STAGING)
    parser.add_argument('--bundle', type=Path)
    args = parser.parse_args()
    _, version = coordinates()
    bundle = args.bundle or ROOT / f'build/central/dokit-{version}-bundle.zip'
    if args.command == 'check':
        check_configuration(args.require_token)
    elif args.command == 'clean':
        # Only the fixed, generated staging repository may be removed.
        if STAGING.exists():
            shutil.rmtree(STAGING)
    elif args.command == 'bundle':
        make_bundle(args.repository, bundle)
    else:
        upload_bundle(bundle)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f'Central publishing: {error}', file=sys.stderr)
        sys.exit(1)
