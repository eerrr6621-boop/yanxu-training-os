"""Fingerprint the installed offline training runtime without importing ML packages.

This is an integrity/reproducibility inventory, not a signature or a claim to
capture the operating system, every transitive dependency, or CPU behaviour.
The caller should store the returned object privately and compare it unchanged
before/after training; no timestamps or process IDs make that comparison noisy.
"""

import hashlib
from importlib import metadata
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import sys


EXPECTED_VERSIONS = {
    "torch": "2.8.0",
    "transformers": "4.57.3",
    "tokenizers": "0.22.1",
    "numpy": "2.2.6",
    "safetensors": "0.8.0",
    "onnx": "1.19.1",
    "onnxruntime": "1.23.2",
}
HASH_CHUNK_BYTES = 1024 * 1024


def _fail(reason):
    raise ValueError("posttrain_runtime:" + reason)


def _normalized_name(value):
    return re.sub(r"[-_.]+", "-", str(value)).lower()


def _ignored(path):
    return "__pycache__" in path.parts or path.suffix.lower() in (".pyc", ".pyo")


def _executable_file(path):
    name = path.name.lower()
    return (path.suffix.lower() in (".py", ".pyi", ".so", ".dylib", ".pyd", ".dll")
            or ".so." in name or ".dylib." in name)


def _hash_file(path):
    """Bound memory use even for large native libraries; reject concurrent edits."""
    path = Path(path)
    try:
        with path.open("rb") as handle:
            before = os.fstat(handle.fileno())
            digest = hashlib.sha256()
            for chunk in iter(lambda: handle.read(HASH_CHUNK_BYTES), b""):
                digest.update(chunk)
            after = os.fstat(handle.fileno())
        current = path.stat()
    except (OSError, RuntimeError) as error:
        _fail("unreadable_file:" + str(path) + ":" + type(error).__name__)
    identity = lambda value: (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns)
    if identity(before) != identity(after) or identity(after) != identity(current):
        _fail("file_changed_while_hashing:" + str(path))
    return {"sha256": digest.hexdigest(), "size_bytes": after.st_size}


def _safe_file(base, relative):
    relative = PurePosixPath(str(relative).replace("\\", "/"))
    if relative.is_absolute() or ".." in relative.parts or not relative.parts:
        _fail("unsafe_distribution_path:" + str(relative))
    path = base.joinpath(*relative.parts)
    try:
        resolved = path.resolve(strict=True)
    except (OSError, RuntimeError) as error:
        _fail("missing_distribution_file:" + str(relative) + ":" + type(error).__name__)
    if not resolved.is_relative_to(base) or not resolved.is_file():
        _fail("distribution_file_escapes_root:" + str(relative))
    return path


def _discover(base, roots, include_all=False):
    """Also catch unrecorded source/native files, not just a stale RECORD list."""
    found = set()
    for root in sorted(roots):
        start = base / root
        if start.is_symlink() or not start.is_dir():
            _fail("unsafe_distribution_directory:" + root)
        for directory, dirs, files in os.walk(start, followlinks=False):
            dirs[:] = sorted(name for name in dirs if name != "__pycache__")
            for name in dirs:
                if (Path(directory) / name).is_symlink():
                    _fail("symlinked_distribution_directory:" + str(Path(directory) / name))
            for name in sorted(files):
                relative = (Path(directory) / name).relative_to(base)
                if not _ignored(relative) and (include_all or _executable_file(relative)):
                    found.add(relative.as_posix())
    return found


def _capture_distribution(name, expected):
    try:
        distribution = metadata.distribution(name)
    except metadata.PackageNotFoundError:
        _fail("package_missing:" + name)
    if distribution.version != expected:
        _fail("package_version_mismatch:" + name + ":expected=" + expected
              + ":actual=" + str(distribution.version))
    if _normalized_name(distribution.metadata.get("Name", "")) != _normalized_name(name):
        _fail("package_identity_mismatch:" + name)
    entries = distribution.files
    if not entries:
        _fail("package_file_inventory_missing:" + name)
    base = Path(distribution.locate_file("")).resolve()
    selected, package_roots, metadata_roots = set(), set(), set()
    for entry in entries:
        relative = PurePosixPath(str(entry).replace("\\", "/"))
        if _ignored(relative):
            continue
        is_metadata = bool(relative.parts) and relative.parts[0].endswith(".dist-info")
        if not is_metadata and not _executable_file(relative):
            continue
        _safe_file(base, relative)
        selected.add(relative.as_posix())
        if is_metadata:
            metadata_roots.add(relative.parts[0])
        elif len(relative.parts) > 1:
            package_roots.add(relative.parts[0])
    if len(metadata_roots) != 1:
        _fail("package_metadata_inventory_invalid:" + name)
    metadata_root = next(iter(metadata_roots))
    required = {metadata_root + "/" + filename for filename in ("METADATA", "RECORD", "WHEEL")}
    if not required <= selected:
        _fail("package_metadata_missing:" + name)
    if not package_roots and not any(_executable_file(PurePosixPath(p)) for p in selected):
        _fail("package_executable_inventory_empty:" + name)
    selected.update(_discover(base, package_roots))
    selected.update(_discover(base, metadata_roots, include_all=True))
    inventory = {relative: _hash_file(_safe_file(base, relative)) for relative in sorted(selected)}
    inventory_digest = hashlib.sha256(json.dumps(inventory, sort_keys=True, separators=(",", ":"))
                                      .encode("utf-8")).hexdigest()
    return {"version": distribution.version, "installation_root": str(base),
            "file_count": len(inventory), "total_bytes": sum(v["size_bytes"] for v in inventory.values()),
            "inventory_sha256": inventory_digest, "files": inventory}


def capture_runtime():
    """Return the actual interpreter and seven exact-version package inventories.

    No torch/transformers/native runtime import, installation, model load, test
    question access, network request or stdout output is performed here.
    """
    executable = Path(sys.executable).absolute()
    if not sys.executable or not executable.is_file():
        _fail("python_executable_missing")
    python = {
        "version": sys.version,
        "version_info": list(sys.version_info),
        "implementation": platform.python_implementation(),
        "cache_tag": sys.implementation.cache_tag,
        "executable": str(executable),
        "resolved_executable": str(executable.resolve()),
        "executable_fingerprint": _hash_file(executable),
    }
    packages = {name: _capture_distribution(name, version) for name, version in EXPECTED_VERSIONS.items()}
    return {
        "schema_version": "yanxu-posttrain-runtime-v1",
        "python": python,
        "platform": {"description": platform.platform(), "system": platform.system(),
                     "release": platform.release(), "machine": platform.machine(),
                     "libc": list(platform.libc_ver())},
        "packages": packages,
        "coverage": {
            "included": "Listed distributions' Python/type-stub source, native libraries and complete dist-info; "
                        "also unrecorded source/native files under their package roots.",
            "excluded": ["__pycache__", ".pyc", ".pyo", "non-executable package data outside dist-info",
                         "other transitive distributions", "OS libraries and dynamic loader", "hardware behaviour"],
            "security_note": "Integrity inventory only; hashes are not signatures or proof of installation authority.",
        },
    }
