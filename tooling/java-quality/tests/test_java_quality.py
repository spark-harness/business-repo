import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


TOOL_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = TOOL_ROOT.parents[1]
SCRIPT = TOOL_ROOT / "java_quality.py"


class JavaQualityTest(unittest.TestCase):
    def run_tool(self, *args):
        return subprocess.run(
            [sys.executable, str(SCRIPT), *args],
            cwd=REPO_ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

    def assert_plan(self, changed_paths, expected_projects, expected_layers):
        result = self.run_tool("plan", *changed_paths)
        self.assertEqual(result.returncode, 0, result.stdout)
        for project in expected_projects:
            self.assertIn(f"JAVA_PROJECT_SELECT {project}", result.stdout)
        for project in {"money", "spring-starter", "applicant-api"} - set(expected_projects):
            self.assertNotIn(f"JAVA_PROJECT_SELECT {project}", result.stdout)
        for layer in expected_layers:
            self.assertIn(f"JAVA_PROJECT_LAYER {layer}", result.stdout)

    def test_money_change_selects_only_money(self):
        self.assert_plan(
            ["packages/java/money/src/main/java/com/spark/common/Money.java"],
            ["money"],
            ["1 money"],
        )

    def test_spring_starter_change_selects_spring_starter_and_applicant_api(self):
        self.assert_plan(
            ["packages/java/spring-starter/src/main/java/com/spark/starter/Port.java"],
            ["spring-starter", "applicant-api"],
            ["1 spring-starter", "2 applicant-api"],
        )

    def test_money_and_spring_starter_changes_parallelize_independent_projects(self):
        self.assert_plan(
            [
                "packages/java/money/src/main/java/com/spark/common/Money.java",
                "packages/java/spring-starter/src/main/java/com/spark/starter/Port.java",
            ],
            ["money", "spring-starter", "applicant-api"],
            ["1 money spring-starter", "2 applicant-api"],
        )

    def test_non_java_change_skips_successfully(self):
        result = self.run_tool("plan", "apps/fides-web/src/app/page.tsx")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("JAVA_PROJECT_SKIP no_java_project_changes", result.stdout)
        self.assertNotIn("JAVA_PROJECT_SELECT", result.stdout)

    def test_quality_tool_change_selects_all_projects(self):
        self.assert_plan(
            ["tooling/java-quality/java_quality.py"],
            ["money", "spring-starter", "applicant-api"],
            ["1 money spring-starter", "2 applicant-api"],
        )

    def test_unknown_java_project_fails(self):
        result = self.run_tool("plan", "packages/java/unknown/pom.xml")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("JAVA_PROJECT_FAIL unknown-java-project", result.stdout)
        self.assertIn("packages/java/unknown/pom.xml", result.stdout)

    def test_run_project_dry_run_selected_uses_plan_without_maven(self):
        with tempfile.TemporaryDirectory() as directory:
            plan_path = Path(directory) / "plan.json"
            self.assertEqual(
                self.run_tool(
                    "plan",
                    "packages/java/spring-starter/pom.xml",
                    "--output",
                    str(plan_path),
                ).returncode,
                0,
            )

            selected = self.run_tool(
                "run-project",
                "applicant-api",
                "--plan",
                str(plan_path),
                "--skip-unselected",
                "--dry-run-selected",
            )
            self.assertEqual(selected.returncode, 0, selected.stdout)
            self.assertIn("JAVA_PROJECT_SELECT applicant-api", selected.stdout)

            skipped = self.run_tool(
                "run-project",
                "money",
                "--plan",
                str(plan_path),
                "--skip-unselected",
                "--dry-run-selected",
            )
            self.assertEqual(skipped.returncode, 0, skipped.stdout)
            self.assertIn("JAVA_PROJECT_SKIP money", skipped.stdout)

    def test_plan_git_with_missing_base_ref_selects_all_projects(self):
        with tempfile.TemporaryDirectory() as directory:
            plan_path = Path(directory) / "plan.json"
            result = self.run_tool(
                "plan-git",
                "--base-ref",
                "origin/__missing_len_114_base__",
                "--output",
                str(plan_path),
            )

            self.assertEqual(result.returncode, 0, result.stdout)
            self.assertIn("JAVA_PROJECT_SELECT money", result.stdout)
            self.assertIn("JAVA_PROJECT_SELECT spring-starter", result.stdout)
            self.assertIn("JAVA_PROJECT_SELECT applicant-api", result.stdout)


if __name__ == "__main__":
    unittest.main()
