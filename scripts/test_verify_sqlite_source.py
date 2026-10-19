import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("sqlite_source", Path(__file__).with_name("verify-sqlite-source.py"))
source = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(source)


class SqliteSourceTest(unittest.TestCase):
    def test_recorded_sources_are_accepted_and_changed_source_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            hashes = {}
            for name in ("sqlite3.c", "sqlite3.h"):
                file = directory / name
                file.write_bytes(name.encode())
                hashes[name] = hashlib.sha256(file.read_bytes()).hexdigest()
            (directory / "source-lock.json").write_text(json.dumps({"version": "3.54.1", "sha256": hashes}))
            self.assertEqual("3.54.1", source.verify_source(directory))
            (directory / "sqlite3.c").write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch: sqlite3.c"):
                source.verify_source(directory)

    def test_source_lock_must_list_both_expected_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "source-lock.json").write_text(json.dumps({"version": "3.54.1", "sha256": {}}))
            with self.assertRaisesRegex(ValueError, "sqlite3.c and sqlite3.h"):
                source.verify_source(directory)


if __name__ == "__main__":
    unittest.main()
