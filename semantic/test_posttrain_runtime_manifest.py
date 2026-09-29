"""Pure temporary-fixture tests: no actual ML-package import or model access."""

from contextlib import redirect_stdout
import hashlib
import importlib.metadata
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import posttrain_runtime_manifest as runtime


class FakeDistribution:
    version = "2.8.0"
    metadata = {"Name": "torch"}

    def __init__(self, root):
        self.root = root
        self.files = [Path("torch/__init__.py"), Path("torch/lib/a.so"), Path("torch/lib/b.dylib"),
                      Path("torch/__pycache__/__init__.cpython-312.pyc"),
                      Path("torch-2.8.0.dist-info/METADATA"), Path("torch-2.8.0.dist-info/RECORD"),
                      Path("torch-2.8.0.dist-info/WHEEL")]

    def locate_file(self, entry):
        return self.root / entry


class RuntimeManifestTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.distribution = FakeDistribution(self.root)
        for file in self.distribution.files:
            path = self.root / file
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((str(file) + "\n").encode())
        self.python = self.root / "python"
        self.python.write_bytes(b"fixture interpreter, never executed")
        self.addCleanup(patch.stopall)
        patch.object(runtime.metadata, "distribution", return_value=self.distribution).start()
        patch.object(runtime, "EXPECTED_VERSIONS", {"torch": "2.8.0"}).start()
        patch.object(runtime.sys, "executable", str(self.python)).start()

    def capture(self):
        return runtime.capture_runtime()

    def test_inventory_and_python_are_actual(self):
        result = self.capture()
        self.assertEqual(result["python"]["executable_fingerprint"]["sha256"],
                         hashlib.sha256(self.python.read_bytes()).hexdigest())
        package = result["packages"]["torch"]
        self.assertEqual(package["version"], "2.8.0")
        self.assertEqual(package["file_count"], 6)
        self.assertIn("torch/lib/a.so", package["files"])
        self.assertIn("torch/lib/b.dylib", package["files"])
        for name in ("METADATA", "RECORD", "WHEEL"):
            self.assertIn("torch-2.8.0.dist-info/" + name, package["files"])
        self.assertNotIn("torch/__pycache__/__init__.cpython-312.pyc", package["files"])
        self.assertIn("OS libraries and dynamic loader", result["coverage"]["excluded"])

    def test_repeat_is_stable_and_bytecode_is_ignored(self):
        before = self.capture()
        (self.root / self.distribution.files[3]).write_bytes(b"changed import cache")
        (self.root / "torch/__pycache__/new.pyc").write_bytes(b"new cache")
        self.assertEqual(before, self.capture())

    def test_source_native_metadata_and_interpreter_edits_are_detected(self):
        for relative in ("torch/__init__.py", "torch/lib/a.so", "torch/lib/b.dylib",
                         "torch-2.8.0.dist-info/METADATA", "torch-2.8.0.dist-info/RECORD", "python"):
            with self.subTest(relative=relative):
                before = self.capture()
                path = self.root / relative
                path.write_bytes(path.read_bytes() + b"change")
                self.assertNotEqual(before, self.capture())

    def test_unrecorded_executable_and_metadata_are_inventoried(self):
        before = self.capture()
        (self.root / "torch/extra.py").write_text("# unrecorded source")
        (self.root / "torch/lib/libnew.so.1").write_bytes(b"native")
        (self.root / "torch-2.8.0.dist-info/entry_points.txt").write_text("[console_scripts]")
        after = self.capture()
        self.assertNotEqual(before, after)
        self.assertEqual(after["packages"]["torch"]["file_count"], 9)

    def test_wrong_version_is_explicit_error(self):
        self.distribution.version = "2.8.1"
        with self.assertRaisesRegex(ValueError, "package_version_mismatch"):
            self.capture()

    def test_wrong_distribution_identity_is_error(self):
        self.distribution.metadata = {"Name": "other"}
        with self.assertRaisesRegex(ValueError, "package_identity_mismatch"):
            self.capture()

    def test_missing_package_is_explicit_error(self):
        with patch.object(runtime.metadata, "distribution", side_effect=importlib.metadata.PackageNotFoundError("torch")):
            with self.assertRaisesRegex(ValueError, "package_missing"):
                self.capture()

    def test_missing_inventory_is_error(self):
        self.distribution.files = None
        with self.assertRaisesRegex(ValueError, "package_file_inventory_missing"):
            self.capture()

    def test_missing_required_metadata_is_error(self):
        self.distribution.files = [p for p in self.distribution.files if p.name != "RECORD"]
        with self.assertRaisesRegex(ValueError, "package_metadata_missing"):
            self.capture()

    def test_missing_declared_source_is_error(self):
        (self.root / "torch/__init__.py").unlink()
        with self.assertRaisesRegex(ValueError, "missing_distribution_file"):
            self.capture()

    def test_path_traversal_is_refused(self):
        self.distribution.files.append(Path("../escaped.py"))
        with self.assertRaisesRegex(ValueError, "unsafe_distribution_path"):
            self.capture()

    def test_outside_symlink_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            external = Path(directory) / "other.py"
            external.write_text("# external")
            (self.root / "torch/extra.py").symlink_to(external)
            with self.assertRaisesRegex(ValueError, "distribution_file_escapes_root"):
                self.capture()

    def test_symlinked_directory_is_refused(self):
        (self.root / "torch/alias").symlink_to(self.root / "torch/lib", target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlinked_distribution_directory"):
            self.capture()

    def test_stream_hash_does_not_use_read_bytes_and_prints_nothing(self):
        (self.root / "torch/lib/a.so").write_bytes(b"x" * (runtime.HASH_CHUNK_BYTES * 2 + 7))
        output = io.StringIO()
        with patch.object(Path, "read_bytes", side_effect=AssertionError("whole file read forbidden")):
            with redirect_stdout(output):
                result = self.capture()
        self.assertEqual(output.getvalue(), "")
        self.assertEqual(result["packages"]["torch"]["files"]["torch/lib/a.so"]["size_bytes"],
                         runtime.HASH_CHUNK_BYTES * 2 + 7)

    def test_no_ml_package_import_is_needed(self):
        import builtins
        original = builtins.__import__
        def guarded(name, *args, **kwargs):
            if name.split(".")[0] in ("torch", "transformers", "tokenizers", "numpy", "safetensors", "onnx", "onnxruntime"):
                raise AssertionError("ML import forbidden: " + name)
            return original(name, *args, **kwargs)
        with patch.object(builtins, "__import__", side_effect=guarded):
            self.capture()


if __name__ == "__main__":
    unittest.main()
