import subprocess
import sys
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT = REPO_ROOT / "scripts" / "contract_dependency_scan.py"
FIXTURES = REPO_ROOT / "tests" / "contract_dependency_scan" / "fixtures"


class ContractDependencyScanTest(unittest.TestCase):
    def run_scan(self, fixture_name, mode):
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--mode",
                mode,
                "--root",
                str(FIXTURES / fixture_name),
                "--config",
                str(REPO_ROOT / "config" / "contract-dependencies.json"),
            ],
            cwd=REPO_ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def run_changed_scan(self, fixture_name, mode, relative_file):
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--mode",
                mode,
                "--root",
                str(FIXTURES / fixture_name),
                "--path",
                relative_file,
                "--config",
                str(REPO_ROOT / "config" / "contract-dependencies.json"),
            ],
            cwd=REPO_ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def assert_scan_passes(self, fixture_name, mode):
        result = self.run_scan(fixture_name, mode)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("No contract dependency violations found.", result.stdout)

    def assert_scan_fails(self, fixture_name, mode, expected):
        result = self.run_scan(fixture_name, mode)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(expected, result.stdout)
        self.assertIn("file=", result.stdout)
        self.assertIn("dependency=", result.stdout)
        self.assertIn("version=", result.stdout)
        self.assertIn("rule=", result.stdout)

    def test_java_formal_passes_in_master_mode(self):
        self.assert_scan_passes("java-formal-pass", "master")

    def test_java_dependency_management_formal_passes_in_master_mode(self):
        self.assert_scan_passes("java-dependency-management-formal-pass", "master")

    def test_java_rc_passes_in_rc_mode(self):
        self.assert_scan_passes("java-rc-pass", "rc")

    def test_java_rc_passes_in_rc_or_formal_mode(self):
        self.assert_scan_passes("java-rc-pass", "rc-or-formal")

    def test_java_rc_fails_in_master_mode(self):
        self.assert_scan_fails("java-rc-pass", "master", "master_requires_formal")

    def test_java_rc_fails_in_formal_only_mode(self):
        self.assert_scan_fails("java-rc-pass", "formal-only", "formal_only_requires_formal")

    def test_java_invalid_rc_fails_in_rc_mode(self):
        self.assert_scan_fails("java-rc-invalid-fail", "rc", "rc_requires_immutable_rc_or_formal")

    def test_java_snapshot_fails_in_rc_mode(self):
        self.assert_scan_fails("java-snapshot-fail", "rc", "snapshot_not_allowed")

    def test_go_formal_passes_in_master_mode(self):
        self.assert_scan_passes("go-formal-pass", "master")

    def test_go_rc_passes_in_rc_mode(self):
        self.assert_scan_passes("go-rc-pass", "rc")

    def test_go_rc_passes_in_rc_or_formal_mode(self):
        self.assert_scan_passes("go-rc-pass", "rc-or-formal")

    def test_go_rc_fails_in_master_mode(self):
        self.assert_scan_fails("go-rc-pass", "master", "master_requires_formal")

    def test_go_invalid_rc_fails_in_rc_mode(self):
        self.assert_scan_fails("go-rc-invalid-fail", "rc", "rc_requires_immutable_rc_or_formal")

    def test_go_pseudo_version_fails_in_rc_mode(self):
        self.assert_scan_fails("go-pseudo-version-fail", "rc", "go_pseudo_version_not_allowed")

    def test_go_local_replace_fails_in_master_mode(self):
        self.assert_scan_fails("go-replace-fail", "master", "go_local_replace_not_allowed")

    def test_changed_go_sum_scans_sibling_go_mod(self):
        result = self.run_changed_scan("go-pseudo-version-fail", "master", "go.sum")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("go_pseudo_version_not_allowed", result.stdout)

    def test_changed_non_dependency_file_does_not_scan_fixture_debt(self):
        result = self.run_changed_scan("java-snapshot-fail", "master", "README.md")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("No contract dependency violations found.", result.stdout)


if __name__ == "__main__":
    unittest.main()
