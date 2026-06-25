#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


TOOL_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOL_ROOT.parents[1]
CONFIG_DIR = TOOL_ROOT / "config"


@dataclass(frozen=True)
class JavaProject:
    name: str
    path: str
    pom: str
    dependencies: tuple[str, ...] = ()


PROJECTS = {
    "money": JavaProject(
        name="money",
        path="packages/java/money",
        pom="packages/java/money/pom.xml",
    ),
    "spring-starter": JavaProject(
        name="spring-starter",
        path="packages/java/spring-starter",
        pom="packages/java/spring-starter/pom.xml",
    ),
    "applicant-api": JavaProject(
        name="applicant-api",
        path="apps/applicant-api",
        pom="apps/applicant-api/pom.xml",
        dependencies=("spring-starter",),
    ),
}

QUALITY_PATHS = (
    "tooling/java-quality/",
)


def normalize_path(path: str) -> str:
    return path.strip().lstrip("./")


def project_for_path(path: str) -> str | None:
    normalized = normalize_path(path)
    for project in PROJECTS.values():
        if normalized == project.path or normalized.startswith(f"{project.path}/"):
            return project.name
    if normalized.startswith("packages/java/") or normalized.startswith("apps/"):
        return "unknown-java-project" if normalized.endswith(".java") or "/java/" in normalized or normalized.endswith("pom.xml") else None
    return None


def expand_dependents(selected: set[str]) -> set[str]:
    expanded = set(selected)
    changed = True
    while changed:
        changed = False
        for project in PROJECTS.values():
            if project.name in expanded:
                continue
            if any(dependency in expanded for dependency in project.dependencies):
                expanded.add(project.name)
                changed = True
    return expanded


def selected_projects(changed_paths: list[str]) -> tuple[list[str], list[str]]:
    selected: set[str] = set()
    unknown: list[str] = []

    for path in changed_paths:
        normalized = normalize_path(path)
        if not normalized:
            continue
        if any(normalized.startswith(prefix) for prefix in QUALITY_PATHS):
            return list(PROJECTS), []
        project_name = project_for_path(normalized)
        if project_name == "unknown-java-project":
            unknown.append(normalized)
        elif project_name:
            selected.add(project_name)

    return sorted(expand_dependents(selected), key=project_order), unknown


def project_order(project_name: str) -> tuple[int, str]:
    project = PROJECTS[project_name]
    return (len(project.dependencies), project.name)


def plan_layers(project_names: list[str]) -> list[list[str]]:
    remaining = set(project_names)
    completed: set[str] = set()
    layers: list[list[str]] = []

    while remaining:
        ready = sorted(
            [
                project_name
                for project_name in remaining
                if all(dependency in completed for dependency in PROJECTS[project_name].dependencies)
            ],
            key=project_order,
        )
        if not ready:
            cycle = " ".join(sorted(remaining))
            raise ValueError(f"cyclic project dependencies: {cycle}")
        layers.append(ready)
        completed.update(ready)
        remaining.difference_update(ready)
    return layers


def all_projects_plan() -> dict:
    projects = list(PROJECTS)
    return {
        "selected": projects,
        "layers": plan_layers(projects),
        "unknown": [],
    }


def build_plan(changed_paths: list[str]) -> dict:
    projects, unknown = selected_projects(changed_paths)
    if unknown:
        return {
            "selected": [],
            "layers": [],
            "unknown": unknown,
        }
    layers = plan_layers(projects)
    return {
        "selected": projects,
        "layers": layers,
        "unknown": [],
    }


def print_plan(plan: dict) -> int:
    if plan["unknown"]:
        for path in plan["unknown"]:
            print(f"JAVA_PROJECT_FAIL unknown-java-project path={path}")
        return 2
    if not plan["selected"]:
        print("JAVA_PROJECT_SKIP no_java_project_changes")
        return 0
    for project_name in plan["selected"]:
        print(f"JAVA_PROJECT_SELECT {project_name}")
    for index, layer in enumerate(plan["layers"], start=1):
        print(f"JAVA_PROJECT_LAYER {index} {' '.join(layer)}")
    return 0


def read_changed_paths_from_git(base_ref: str) -> list[str]:
    if not subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", f"{base_ref}^{{commit}}"],
        cwd=REPO_ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    ).returncode == 0:
        return []
    result = subprocess.run(
        ["git", "diff", "--name-only", base_ref, "HEAD"],
        cwd=REPO_ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        print(result.stderr, end="", file=sys.stderr)
        return []
    return [line for line in result.stdout.splitlines() if line.strip()]


def write_plan(plan: dict, output: Path | None) -> None:
    if output is None:
        return
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(plan, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def project_is_selected(project_name: str, plan_path: Path | None) -> bool:
    if plan_path is None:
        return True
    plan = json.loads(plan_path.read_text(encoding="utf-8"))
    return project_name in set(plan.get("selected", []))


def maven_command(project_name: str, goal: str) -> list[str]:
    project = PROJECTS[project_name]
    base = [
        "mvn",
        "-B",
        "-f",
        project.pom,
        f"-Djava.quality.config.dir={CONFIG_DIR}",
    ]
    if goal == "install":
        return [*base, "spotless:check", "checkstyle:check", "test", "spotbugs:check", "install"]
    return [*base, "spotless:check", "checkstyle:check", "test", "spotbugs:check"]


def run_project(project_name: str, plan_path: Path | None, skip_unselected: bool) -> int:
    if project_name not in PROJECTS:
        print(f"JAVA_PROJECT_FAIL {project_name} reason=unknown-project")
        return 2

    goal = "install" if project_name == "spring-starter" else "verify"
    print(f"JAVA_PROJECT_START {project_name}")
    result = subprocess.run(maven_command(project_name, goal), cwd=REPO_ROOT, check=False)
    if result.returncode != 0:
        print(f"JAVA_PROJECT_FAIL {project_name}")
        return result.returncode
    print(f"JAVA_PROJECT_PASS {project_name}")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Plan and run business-repo Java quality gates.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    plan_parser = subparsers.add_parser("plan")
    plan_parser.add_argument("paths", nargs="*")
    plan_parser.add_argument("--output", type=Path)

    plan_git_parser = subparsers.add_parser("plan-git")
    plan_git_parser.add_argument("--base-ref", required=True)
    plan_git_parser.add_argument("--output", type=Path, required=True)

    run_parser = subparsers.add_parser("run-project")
    run_parser.add_argument("project", choices=sorted(PROJECTS))
    run_parser.add_argument("--plan", type=Path)
    run_parser.add_argument("--skip-unselected", action="store_true")
    run_parser.add_argument("--dry-run-selected", action="store_true")

    args = parser.parse_args(argv)

    if args.command == "plan":
        plan = build_plan(args.paths)
        write_plan(plan, args.output)
        return print_plan(plan)
    if args.command == "plan-git":
        changed_paths = read_changed_paths_from_git(args.base_ref)
        plan = build_plan(changed_paths) if changed_paths else all_projects_plan()
        write_plan(plan, args.output)
        return print_plan(plan)
    if args.command == "run-project":
        if args.skip_unselected and not project_is_selected(args.project, args.plan):
            print(f"JAVA_PROJECT_SKIP {args.project}")
            return 0
        if args.dry_run_selected:
            print(f"JAVA_PROJECT_SELECT {args.project}")
            return 0
        return run_project(args.project, args.plan, args.skip_unselected)
    raise AssertionError(f"unsupported command: {args.command}")


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
