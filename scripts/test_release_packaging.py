"""Small synthetic checks for the build/package boundary; no app data or real archives."""
import sys
sys.dont_write_bytecode = True

import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock

import build_verified_candidate as candidate
import prepare_release as release
from release_runtime import RUNTIME_FILES, RUNTIME_JARS, runtime_snapshot


def fingerprint(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def record(path, value):
    path.write_text(json.dumps(value) + '\n')


class ReleasePackagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='release-boundary-synthetic-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.app = self.root / 'app'
        for name in RUNTIME_FILES + ('src/com/training/Main.java', 'web/index.html',
                                    'lib/ecj.jar', 'data/private.txt', 'scripts/Fixture.java'):
            path = self.app / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('SYNTHETIC ' + name + '\n')

    def build(self, name='build', mutation=None):
        destination = self.root / name

        def compiler(command, **kwargs):
            out = Path(command[command.index('-d') + 1]) / 'com/training/Main.class'
            out.parent.mkdir(parents=True)
            out.write_bytes(b'synthetic product class')
            if mutation:
                mutation()
            return subprocess.CompletedProcess(command, 0)

        with mock.patch.object(candidate, '__file__', str(self.app / 'scripts/build_verified_candidate.py')):
            with mock.patch.object(candidate.subprocess, 'run', side_effect=compiler):
                candidate.build('synthetic-compiler', destination)
        record(destination / 'web-manifest.json', {'index.html': fingerprint(self.app / 'web/index.html')})
        record(destination / 'frontend-http.json', {'ok': True, 'http': {'enabled': True}})
        return destination

    def test_normal_package_contains_only_frozen_product_inputs(self):
        build = self.build()
        frozen = json.loads((build / 'runtime-manifest.json').read_text())
        self.assertEqual(set(frozen), set(RUNTIME_FILES))
        self.assertEqual(json.loads((build / 'build.json').read_text())['runtime_dependencies'],
                         {name: frozen[name] for name in RUNTIME_JARS})
        receipt = release.prepare(self.app, build, self.root / 'package')
        self.assertTrue(receipt['archive_verified'])
        expected = {'out/com/training/Main.class', 'web/index.html', *RUNTIME_FILES}
        with tarfile.open(receipt['archive']) as archive:
            self.assertEqual(set(archive.getnames()), expected | {'release-manifest.json'})
            manifest = json.load(archive.extractfile('release-manifest.json'))
            self.assertEqual(set(manifest['files']), expected)
            for name, digest in manifest['files'].items():
                self.assertEqual(hashlib.sha256(archive.extractfile(name).read()).hexdigest(), digest)
            self.assertEqual({name: manifest['files'][name] for name in RUNTIME_FILES}, frozen)

    def test_runtime_changes_after_build_rejected(self):
        build = self.build()
        for name in ('lib/h2.jar', 'lib/ip2region/ip2region_v4.xdb'):
            with self.subTest(name=name):
                path = self.app / name
                original = path.read_bytes()
                path.write_bytes(original + b'changed after verification')
                with self.assertRaisesRegex(ValueError, 'Runtime inputs changed'):
                    release.prepare(self.app, build, None)
                path.write_bytes(original)

    def test_missing_extra_and_linked_runtime_inputs_rejected(self):
        build = self.build()
        for name in RUNTIME_FILES:
            with self.subTest(missing=name):
                path = self.app / name
                original = path.read_bytes()
                path.unlink()
                with self.assertRaises(ValueError):
                    release.prepare(self.app, build, None)
                path.write_bytes(original)
        for name in ('lib/ip2region/accounts.json', 'lib/ip2region/extra/note.txt'):
            with self.subTest(extra=name):
                path = self.app / name
                path.parent.mkdir(exist_ok=True)
                path.write_text('synthetic private marker')
                with self.assertRaisesRegex(ValueError, 'allowlist'):
                    release.prepare(self.app, build, None)
                path.unlink()
                if path.parent.name == 'extra':
                    path.parent.rmdir()
        jar = self.app / 'lib/h2.jar'
        original = jar.read_bytes()
        target = self.root / 'linked.jar'
        target.write_bytes(original)
        jar.unlink()
        jar.symlink_to(target)
        with self.assertRaisesRegex(ValueError, 'plain runtime dependency'):
            release.prepare(self.app, build, None)
        jar.unlink()
        jar.write_bytes(original)
        for name in ('lib/ip2region', 'lib'):
            with self.subTest(linked_directory=name):
                path = self.app / name
                target = self.root / ('moved-' + path.name)
                path.rename(target)
                path.symlink_to(target, target_is_directory=True)
                with self.assertRaisesRegex(ValueError, 'plain runtime directory'):
                    release.prepare(self.app, build, None)
                path.unlink()
                target.rename(path)

    def test_old_or_incomplete_runtime_manifests_rejected(self):
        build = self.build()
        path = build / 'runtime-manifest.json'
        original = json.loads(path.read_text())
        path.unlink()
        with self.assertRaisesRegex(ValueError, 'create and verify a new build'):
            release.prepare(self.app, build, None)
        for changed in ({}, {**original, 'lib/extra.jar': '0' * 64},
                        {**original, 'lib/h2.jar': 'not-a-sha256'}):
            with self.subTest(manifest=changed):
                record(path, changed)
                with self.assertRaisesRegex(ValueError, 'complete frozen runtime manifest'):
                    release.prepare(self.app, build, None)

    def test_postcheck_changes_cannot_become_a_new_manifest_baseline(self):
        build = self.build()
        original_check = release.checked_runtime
        for index, path in enumerate((build / 'out/com/training/Main.class',
                                      self.app / 'web/index.html', self.app / 'lib/h2.jar')):
            with self.subTest(path=str(path)):
                original = path.read_bytes()

                def after_checked(app, manifest):
                    verified = original_check(app, manifest)
                    path.write_bytes(original + b'concurrent modification after all manifest checks')
                    return verified

                output = self.root / ('postcheck-package-' + str(index))
                with mock.patch.object(release, 'checked_runtime', side_effect=after_checked):
                    with self.assertRaisesRegex(ValueError, 'Input changed while packaging'):
                        release.prepare(self.app, build, output)
                self.assertFalse((output / 'package-receipt.json').exists())
                path.write_bytes(original)

    def test_source_drift_after_build_rejected(self):
        build = self.build()
        (self.app / 'src/com/training/Main.java').write_text('changed source')
        with self.assertRaisesRegex(ValueError, 'Product sources changed'):
            release.prepare(self.app, build, None)

    def test_build_rejects_source_and_runtime_changes_during_compile(self):
        for index, name in enumerate(('src/com/training/Main.java', 'lib/h2.jar',
                                      'lib/ip2region/ip2region_v6.xdb')):
            with self.subTest(name=name):
                path = self.app / name
                original = path.read_bytes()
                destination = 'drifting-build-' + str(index)
                with self.assertRaisesRegex((ValueError, RuntimeError), 'changed'):
                    self.build(destination, lambda: path.write_bytes(original + b'changed while compiling'))
                self.assertFalse((self.root / destination / 'runtime-manifest.json').exists())
                self.assertFalse((self.root / destination / 'build.json').exists())
                path.write_bytes(original)

    def test_runtime_snapshot_requires_exact_asset_set(self):
        self.assertEqual(set(runtime_snapshot(self.app)), set(RUNTIME_FILES))
        extra = self.app / 'lib/ip2region/extra'
        extra.mkdir()
        with self.assertRaisesRegex(ValueError, 'allowlist'):
            runtime_snapshot(self.app)


if __name__ == '__main__':
    unittest.main(verbosity=2)
