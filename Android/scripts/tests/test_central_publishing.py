import contextlib
import hashlib
import importlib.util
import io
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location('central_publishing', Path(__file__).resolve().parents[1] / 'central-publishing.py')
central = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(central)


class CentralPublishingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repository = Path(self.temporary.name) / 'repository'
        self.bundle = Path(self.temporary.name) / 'release.zip'
        self.group, self.version = central.coordinates()
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, 'w') as jar:
            jar.writestr('README.md', 'Publication fixture')
        self.archive = archive.getvalue()
        for (group, artifact), packaging in central.artifacts(self.group).items():
            directory = self.repository / group.replace('.', '/') / artifact / self.version
            directory.mkdir(parents=True)
            prefix = directory / f'{artifact}-{self.version}'
            dependency = (f'<dependencies><dependency><groupId>{self.group}</groupId>'
                          f'<artifactId>dokitx-plugin</artifactId><version>{self.version}</version>'
                          '</dependency></dependencies>') if packaging == 'pom' else ''
            pom = f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion><groupId>{group}</groupId>
                <artifactId>{artifact}</artifactId><version>{self.version}</version>
                <name>DoKit</name><description>DoKit fixture</description><url>https://github.com/qqnp1100/DoKit</url>
                <licenses><license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses>
                <developers><developer><name>xzin</name><email>798494008@163.com</email></developer></developers>
                <scm><connection>scm:git:https://github.com/qqnp1100/DoKit.git</connection><url>https://github.com/qqnp1100/DoKit</url></scm>
                {dependency}</project>'''
            self.write_signed(Path(str(prefix) + '.pom'), pom.encode())
            if packaging != 'pom':
                for suffix in ['.' + packaging, '-sources.jar', '-javadoc.jar']:
                    self.write_signed(Path(str(prefix) + suffix), self.archive)

    def write_signed(self, path, content):
        path.write_bytes(content)
        # Fixture signatures test bundle structure; actual Gradle signing is
        # exercised separately with a temporary PGP key during release QA.
        Path(str(path) + '.asc').write_text('-----BEGIN PGP SIGNATURE-----\nfixture\n-----END PGP SIGNATURE-----\n')

    def make_bundle(self):
        with contextlib.redirect_stdout(io.StringIO()):
            central.make_bundle(self.repository, self.bundle)

    def test_bundle_contains_all_publications_and_required_checksums_without_uploading(self):
        (self.repository / 'maven-metadata.xml').write_text('not a release artifact')
        with patch.object(central.urllib.request, 'urlopen') as request:
            self.make_bundle()
        request.assert_not_called()
        with zipfile.ZipFile(self.bundle) as bundle:
            self.assertEqual(15, sum(name.endswith('.pom') for name in bundle.namelist()))
            self.assertNotIn('maven-metadata.xml', bundle.namelist())
            for name in bundle.namelist():
                if name.endswith('.jar'):
                    expected = hashlib.sha1(bundle.read(name)).hexdigest().encode()
                    self.assertEqual(expected, bundle.read(name + '.sha1'))

    def test_unsigned_artifact_is_rejected_before_a_bundle_is_written(self):
        next(self.repository.rglob('*.jar.asc')).unlink()
        with self.assertRaisesRegex(ValueError, 'Missing PGP signature'):
            self.make_bundle()
        self.assertFalse(self.bundle.exists())

    def test_foreign_plugin_marker_cannot_enter_the_central_bundle(self):
        directory = self.repository / f'com/didi/dokit/legacy/{self.version}'
        directory.mkdir(parents=True)
        (directory / 'legacy.pom').write_text('<project/>')
        with self.assertRaisesRegex(ValueError, 'exactly the enabled SDK modules'):
            self.make_bundle()

    def test_old_internal_group_is_rejected(self):
        pom = next(self.repository.rglob('*.gradle.plugin-*.pom'))
        pom.write_text(pom.read_text().replace(f'<groupId>{self.group}</groupId>', '<groupId>com.didichuxing.doraemonkit</groupId>'))
        with self.assertRaisesRegex(ValueError, 'Incorrect internal dependency'):
            self.make_bundle()

    def test_upload_rejects_tampered_checksums_without_network_access(self):
        self.make_bundle()
        with zipfile.ZipFile(self.bundle) as bundle:
            files = {name: bundle.read(name) for name in bundle.namelist()}
        files[next(name for name in files if name.endswith('.jar.sha1'))] = b'incorrect'
        with zipfile.ZipFile(self.bundle, 'w') as bundle:
            for name, data in files.items():
                bundle.writestr(name, data)
        with patch.object(central.urllib.request, 'urlopen') as request:
            with self.assertRaisesRegex(ValueError, 'checksum'):
                central.upload_bundle(self.bundle)
        request.assert_not_called()

    def test_portal_upload_uses_bearer_token_and_user_managed_deployment(self):
        self.make_bundle()
        response = io.BytesIO(b'00000000-0000-0000-0000-000000000001')
        values = {'DOKIT_PUBLISH_USERNAME': 'test-user', 'DOKIT_PUBLISH_PASSWORD': 'test-password'}
        with patch.object(central, 'settings', return_value=values), \
                patch.object(central.urllib.request, 'urlopen', return_value=response) as request, \
                contextlib.redirect_stdout(io.StringIO()) as output:
            central.upload_bundle(self.bundle)
        call = request.call_args.args[0]
        self.assertTrue(call.full_url.startswith('https://central.sonatype.com/api/v1/publisher/upload?'))
        self.assertIn('publishingType=USER_MANAGED', call.full_url)
        self.assertEqual('Bearer dGVzdC11c2VyOnRlc3QtcGFzc3dvcmQ=', call.get_header('Authorization'))
        self.assertIn(b'name="bundle"', call.data)
        self.assertNotIn('test-password', output.getvalue())
        self.assertEqual('00000000-0000-0000-0000-000000000001\n', self.bundle.with_suffix('.deployment-id').read_text())

    def test_properties_preserve_unicode_paths_and_escaped_passwords(self):
        path = Path(self.temporary.name) / 'local.properties'
        path.write_text('path=/tmp/密钥\\ dir/key.asc\npassword=p\\:a\\=ss\\\\word\nkey=first\\nsecond\n')
        values = central.read_properties(path)
        self.assertEqual('/tmp/密钥 dir/key.asc', values['path'])
        self.assertEqual('p:a=ss\\word', values['password'])
        self.assertEqual('first\nsecond', values['key'])


if __name__ == '__main__':
    unittest.main()
