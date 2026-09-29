#!/usr/bin/env python3
"""Package an explicitly verified product build; never upload, stop a service, or copy data."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tarfile
from release_runtime import checked_runtime


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def files(root):
    if not root.is_dir() or root.is_symlink():
        raise ValueError('A real product directory is required: ' + str(root))
    result = []
    for item in sorted(root.rglob('*')):
        if item.is_symlink():
            raise ValueError('Release inputs cannot contain symbolic links: ' + str(item))
        if item.is_file():
            result.append(item)
    return result


def checked_manifest(root, manifest):
    actual = {str(p.relative_to(root)): sha(p) for p in files(root)}
    if actual != manifest:
        raise ValueError('Inputs no longer match their reviewed manifest: ' + str(root))
    return dict(manifest)


def prepare(app, build, output):
    sources = json.loads((build / 'source-manifest.json').read_text())
    expected_sources = {str(p.relative_to(app)): sha(p) for p in sorted((app / 'src/com/training').glob('*.java'))}
    if sources != expected_sources:
        raise ValueError('Product sources changed after compilation')
    runtime_path = build / 'runtime-manifest.json'
    if not runtime_path.is_file():
        raise ValueError('Build has no frozen runtime manifest; create and verify a new build')
    classes = checked_manifest(build / 'out', json.loads((build / 'product-class-manifest.json').read_text()))
    if not classes or any(not name.endswith('.class') or not name.startswith('com/training/') for name in classes):
        raise ValueError('Only product classes may be packaged')
    if any(Path(name).stem.endswith(('Test', 'Fixture', 'Probe')) for name in classes):
        raise ValueError('Use the product output, not an output containing test classes')
    web = checked_manifest(app / 'web', json.loads((build / 'web-manifest.json').read_text()))
    frontend = json.loads((build / 'frontend-http.json').read_text())
    if frontend.get('ok') is not True or frontend.get('http', {}).get('enabled') is not True:
        raise ValueError('A successful check of the served static resources is required')
    runtime = checked_runtime(app, json.loads(runtime_path.read_text()))

    payload = [(build / 'out' / name, 'out/' + name) for name in classes]
    payload += [(app / 'web' / name, 'web/' + name) for name in web]
    payload += [(app / name, name) for name in runtime]
    frozen = {'out/' + name: value for name, value in classes.items()}
    frozen.update({'web/' + name: value for name, value in web.items()})
    frozen.update(runtime)
    for path, name in payload:
        if any(part.startswith('.') for part in Path(name).parts) or path.suffix.lower() in {'.db', '.pem', '.key', '.xlsx', '.docx', '.log'}:
            raise ValueError('Private or unexpected file in the public release inputs: ' + name)
    size = sum(path.stat().st_size for path, _ in payload)
    if size > 192 * 1024 * 1024:
        raise ValueError('Unexpected package size; inspect before copying more than 192 MiB')
    manifest = {'kind': 'unreleased-product-candidate', 'files': frozen,
                'file_count': len(payload), 'uncompressed_bytes': size,
                'contains_database': False, 'contains_account_materials': False,
                'deployment_performed': False}
    if output is None:
        return {key: value for key, value in manifest.items() if key != 'files'}
    if output.exists() or not output.parent.is_dir():
        raise ValueError('Choose a new output directory under an existing parent')
    output.mkdir(mode=0o700)
    manifest_path = output / 'release-manifest.json'
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    manifest_path.write_bytes(manifest_bytes)
    archive = output / 'product.tar.gz'
    # One archive, read directly from the frozen files. No copied working tree or duplicated DB.
    with tarfile.open(archive, 'w:gz') as tar:
        for path, name in payload:
            if sha(path) != manifest['files'][name]:
                raise ValueError('Input changed while packaging: ' + name)
            tar.add(path, arcname=name, recursive=False)
        tar.add(manifest_path, arcname='release-manifest.json', recursive=False)
    with tarfile.open(archive, 'r:gz') as tar:
        members = tar.getmembers()
        expected_members = set(frozen) | {'release-manifest.json'}
        if len(members) != len(expected_members) or {m.name for m in members} != expected_members:
            raise ValueError('Archive members do not match the frozen release manifest')
        for member in members:
            if not member.isfile():
                raise ValueError('Unexpected archive member')
            if member.name == 'release-manifest.json':
                if tar.extractfile(member).read() != manifest_bytes:
                    raise ValueError('Archive manifest failed verification')
                continue
            stream = tar.extractfile(member)
            h = hashlib.sha256()
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                h.update(chunk)
            if h.hexdigest() != manifest['files'][member.name]:
                raise ValueError('Archive content failed verification: ' + member.name)
    receipt = {key: value for key, value in manifest.items() if key != 'files'}
    receipt.update(archive=str(archive), archive_sha256=sha(archive), archive_verified=True)
    (output / 'package-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--output', type=Path, help='Omit for a read-only packaging preflight')
    args = parser.parse_args()
    os.umask(0o077)
    try:
        print(json.dumps(prepare(Path(__file__).resolve().parents[1], args.build.resolve(), args.output), ensure_ascii=False))
    except (ValueError, OSError, KeyError, json.JSONDecodeError) as error:
        raise SystemExit('Release candidate not prepared: ' + str(error))
