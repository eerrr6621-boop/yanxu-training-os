"""The complete runtime input boundary shared by candidate builds and packaging."""
import hashlib
import re


RUNTIME_JARS = ('lib/h2.jar', 'lib/pdfbox-app-3.0.8.jar', 'lib/ip2region-3.3.7.jar')
REGION_ASSETS = ('LICENSE.md', 'README.md', 'ip2region_v4.xdb', 'ip2region_v6.xdb')
RUNTIME_FILES = RUNTIME_JARS + tuple('lib/ip2region/' + name for name in REGION_ASSETS)


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def runtime_snapshot(app):
    for root in (app / 'lib', app / 'lib/ip2region'):
        if not root.is_dir() or root.is_symlink():
            raise ValueError('A plain runtime directory is required: ' + str(root))
    region = app / 'lib/ip2region'
    entries = list(region.iterdir())
    if {p.name for p in entries} != set(REGION_ASSETS):
        raise ValueError('Runtime region assets must exactly match the release allowlist')
    for name in RUNTIME_FILES:
        path = app / name
        if not path.is_file() or path.is_symlink():
            raise ValueError('Missing plain runtime dependency: ' + name)
    return {name: digest(app / name) for name in RUNTIME_FILES}


def checked_runtime(app, manifest):
    if (not isinstance(manifest, dict) or set(manifest) != set(RUNTIME_FILES)
            or any(not isinstance(value, str) or re.fullmatch('[0-9a-f]{64}', value) is None
                   for value in manifest.values())):
        raise ValueError('A complete frozen runtime manifest is required')
    if runtime_snapshot(app) != manifest:
        raise ValueError('Runtime inputs changed after the verified snapshot')
    return dict(manifest)
