#!/usr/bin/env python3
"""Build product-only classes and durable fingerprints; never start or deploy the app."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from release_runtime import RUNTIME_JARS, checked_runtime, runtime_snapshot


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(java, destination):
    app = Path(__file__).resolve().parent.parent
    sources = sorted((app / 'src/com/training').glob('*.java'))
    if not sources or any(p.is_symlink() for p in sources):
        raise ValueError('Plain product sources are required')
    before = {str(p.relative_to(app)): digest(p) for p in sources}
    runtime = runtime_snapshot(app)
    destination = destination.absolute()
    if destination.exists() or not destination.parent.is_dir():
        raise ValueError('Choose a new build directory under an existing parent')
    destination.mkdir(mode=0o700)
    out = destination / 'out'
    out.mkdir()
    dependencies = [app / name for name in RUNTIME_JARS]
    with (destination / 'compile.log').open('w') as log:
        result = subprocess.run([java, '-jar', str(app / 'lib/ecj.jar'), '-17', '-encoding', 'UTF-8', '-nowarn',
                                 '-cp', ':'.join(map(str, dependencies)), '-d', str(out), *map(str, sources)],
                                cwd=app, stdout=log, stderr=log)
    if result.returncode:
        raise RuntimeError('Compilation failed; see ' + str(destination / 'compile.log'))
    after = {str(p.relative_to(app)): digest(p) for p in sorted((app / 'src/com/training').glob('*.java'))}
    if before != after:
        raise RuntimeError('Product sources changed during compilation')
    checked_runtime(app, runtime)
    classes = {str(p.relative_to(out)): digest(p) for p in sorted(out.rglob('*.class'))}
    if not classes:
        raise RuntimeError('Compiler produced no product classes')
    (destination / 'source-manifest.json').write_text(json.dumps(before, indent=2) + '\n')
    (destination / 'product-class-manifest.json').write_text(json.dumps(classes, indent=2) + '\n')
    (destination / 'runtime-manifest.json').write_text(json.dumps(runtime, indent=2) + '\n')
    info = {'build': str(destination), 'sources': len(sources), 'classes': len(classes),
            'runtime_dependencies': {name: runtime[name] for name in RUNTIME_JARS},
            'runtime_manifest': 'runtime-manifest.json', 'runtime_files': len(runtime),
            'product_only': True, 'started': False, 'deployed': False}
    (destination / 'build.json').write_text(json.dumps(info, indent=2) + '\n')
    return info


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--build-dir', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(build(args.java, args.build_dir), ensure_ascii=False))
