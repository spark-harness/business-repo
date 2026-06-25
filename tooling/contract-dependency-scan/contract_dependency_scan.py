#!/usr/bin/env python3
import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


FORMAL_JAVA = re.compile(r"^\d+\.\d+\.\d+$")
FORMAL_GO = re.compile(r"^v\d+\.\d+\.\d+$")
JAVA_RC = re.compile(r"^\d+\.\d+\.\d+-rc\.[A-Za-z]+-\d+\.\d{8}\.[0-9a-fA-F]{7,40}$")
GO_RC = re.compile(r"^v\d+\.\d+\.\d+-rc\.[a-z]+[0-9]+(?:-[a-z0-9]+)*\.\d{8}\.[0-9a-f]{7,40}$", re.IGNORECASE)
GO_PSEUDO = re.compile(r"^v\d+\.\d+\.\d+-\d{14}-[0-9a-f]{12}$", re.IGNORECASE)
PROPERTY_REF = re.compile(r"^\$\{([^}]+)}$")


@dataclass(frozen=True)
class Violation:
    file: Path
    dependency: str
    version: str
    rule: str
    message: str


def load_config(path):
    with path.open(encoding="utf-8") as config_file:
        raw = json.load(config_file)
    return {
        "maven": {
            (item["groupId"], item["artifactId"])
            for item in raw.get("maven", [])
        },
        "go": [
            item["modulePrefix"]
            for item in raw.get("go", [])
        ],
    }


def strip_namespace(tag):
    return tag.rsplit("}", 1)[-1]


def children_named(element, name):
    return [child for child in list(element) if strip_namespace(child.tag) == name]


def first_text(element, name):
    for child in children_named(element, name):
        return (child.text or "").strip()
    return ""


def collect_maven_properties(root):
    properties = {}
    for properties_node in children_named(root, "properties"):
        for child in list(properties_node):
            properties[strip_namespace(child.tag)] = (child.text or "").strip()
    return properties


def resolve_maven_version(raw_version, properties):
    match = PROPERTY_REF.match(raw_version)
    if not match:
        return raw_version
    return properties.get(match.group(1), raw_version)


def dependency_identity(dependency_node):
    return first_text(dependency_node, "groupId"), first_text(dependency_node, "artifactId")


def managed_maven_versions(root, properties):
    versions = {}
    for dependency_management in children_named(root, "dependencyManagement"):
        for dependencies in children_named(dependency_management, "dependencies"):
            for dependency_node in children_named(dependencies, "dependency"):
                group_id, artifact_id = dependency_identity(dependency_node)
                raw_version = first_text(dependency_node, "version")
                if group_id and artifact_id and raw_version:
                    versions[(group_id, artifact_id)] = resolve_maven_version(raw_version, properties)
    return versions


def classify_java_version(version):
    upper = version.upper()
    if "SNAPSHOT" in upper:
        return "snapshot"
    if FORMAL_JAVA.match(version):
        return "formal"
    if JAVA_RC.match(version):
        return "rc"
    return "branch_or_unclassified"


def classify_go_version(version):
    if GO_PSEUDO.match(version):
        return "pseudo"
    if FORMAL_GO.match(version):
        return "formal"
    if GO_RC.match(version):
        return "rc"
    return "branch_or_unclassified"


def normalize_mode(mode):
    aliases = {
        "rc": "rc-or-formal",
        "master": "formal-only",
    }
    return aliases.get(mode, mode)


def violation_for_stage(file_path, dependency, version, stage, mode, language):
    original_mode = mode
    mode = normalize_mode(mode)
    if stage == "snapshot":
        return Violation(file_path, dependency, version, "snapshot_not_allowed", "Contract SNAPSHOT dependencies are not allowed in CI gates.")
    if language == "go" and stage == "pseudo":
        return Violation(file_path, dependency, version, "go_pseudo_version_not_allowed", "Go pseudo-versions are development dependencies.")
    if mode == "formal-only":
        if stage != "formal":
            rule = "formal_only_requires_formal" if original_mode == "formal-only" else "master_requires_formal"
            return Violation(file_path, dependency, version, rule, "Release-bound changes may consume only formal contract versions.")
        return None
    if mode == "rc-or-formal":
        if stage not in {"formal", "rc"}:
            return Violation(file_path, dependency, version, "rc_requires_immutable_rc_or_formal", "RC gate requires a formal version or immutable RC version.")
        return None
    raise ValueError(f"unsupported mode: {mode}")


def scan_pom(path, config, mode):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        return [Violation(path, "pom.xml", "unparseable", "pom_parse_error", str(exc))]

    properties = collect_maven_properties(root)
    managed_versions = managed_maven_versions(root, properties)
    violations = []
    for dependencies in children_named(root, "dependencies"):
        for dependency_node in children_named(dependencies, "dependency"):
            group_id, artifact_id = dependency_identity(dependency_node)
            if (group_id, artifact_id) not in config["maven"]:
                continue

            raw_version = first_text(dependency_node, "version")
            version = resolve_maven_version(raw_version, properties) if raw_version else managed_versions.get((group_id, artifact_id), "")
            coordinate = f"{group_id}:{artifact_id}"
            if not version:
                violations.append(Violation(path, coordinate, "<missing>", "unclassified_contract_dependency", "Contract dependency version is missing."))
                continue
            if PROPERTY_REF.match(version):
                violations.append(Violation(path, coordinate, version, "unclassified_contract_dependency", "Contract dependency version property could not be resolved."))
                continue

            violation = violation_for_stage(path, coordinate, version, classify_java_version(version), mode, "java")
            if violation:
                violations.append(violation)
    return violations


def split_go_fields(line):
    return line.split("//", 1)[0].strip().split()


def parse_go_mod(path):
    requires = []
    replaces = []
    in_require = False
    in_replace = False

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.split("//", 1)[0].strip()
        if not line:
            continue
        if line == ")":
            in_require = False
            in_replace = False
            continue
        if line == "require (":
            in_require = True
            continue
        if line == "replace (":
            in_replace = True
            continue
        if line.startswith("require "):
            parts = split_go_fields(line[len("require "):])
            if len(parts) >= 2:
                requires.append((parts[0], parts[1]))
            continue
        if line.startswith("replace "):
            replacement = line[len("replace "):]
            if "=>" in replacement:
                left, right = [part.strip() for part in replacement.split("=>", 1)]
                left_parts = split_go_fields(left)
                right_parts = split_go_fields(right)
                if left_parts and right_parts:
                    replaces.append((left_parts[0], right_parts[0], right_parts[1] if len(right_parts) > 1 else ""))
            continue
        if in_require:
            parts = split_go_fields(line)
            if len(parts) >= 2:
                requires.append((parts[0], parts[1]))
            continue
        if in_replace and "=>" in line:
            left, right = [part.strip() for part in line.split("=>", 1)]
            left_parts = split_go_fields(left)
            right_parts = split_go_fields(right)
            if left_parts and right_parts:
                replaces.append((left_parts[0], right_parts[0], right_parts[1] if len(right_parts) > 1 else ""))
    return requires, replaces


def is_contract_go_module(module, config):
    return any(module.startswith(prefix) for prefix in config["go"])


def is_local_replace(target):
    return target.startswith(".") or target.startswith("/") or target.startswith("..")


def scan_go_mod(path, config, mode):
    requires, replaces = parse_go_mod(path)
    violations = []

    for module, version in requires:
        if not is_contract_go_module(module, config):
            continue
        violation = violation_for_stage(path, module, version, classify_go_version(version), mode, "go")
        if violation:
            violations.append(violation)

    for module, target, target_version in replaces:
        if not is_contract_go_module(module, config):
            continue
        version = target if not target_version else f"{target} {target_version}"
        if is_local_replace(target):
            violations.append(Violation(path, module, version, "go_local_replace_not_allowed", "Contract modules must not use local replace directives."))
        else:
            stage = classify_go_version(target_version) if target_version else "branch_or_unclassified"
            violation = violation_for_stage(path, module, version, stage, mode, "go")
            if violation:
                violations.append(violation)
    return violations


def should_ignore(path, root):
    ignored_parts = {".git", "target", "build", "node_modules", ".next", "dist", "__pycache__"}
    relative_parts = path.relative_to(root).parts
    if relative_parts[:3] == ("tooling", "contract-dependency-scan", "fixtures"):
        return True
    return bool(ignored_parts.intersection(relative_parts))


def find_files(root):
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if should_ignore(path, root):
            continue
        if path.name in {"pom.xml", "go.mod"}:
            yield path


def find_selected_files(root, selected_paths):
    seen = set()
    for selected_path in selected_paths:
        candidate = selected_path if selected_path.is_absolute() else root / selected_path
        if candidate.name == "go.sum":
            candidate = candidate.with_name("go.mod")
        if candidate.name not in {"pom.xml", "go.mod"}:
            continue
        candidate = candidate.resolve()
        if not candidate.exists() or not candidate.is_file():
            continue
        if should_ignore(candidate, root):
            continue
        if candidate in seen:
            continue
        seen.add(candidate)
        yield candidate


def format_violation(violation, root):
    try:
        display_path = violation.file.relative_to(root)
    except ValueError:
        display_path = violation.file
    return (
        f"file={display_path} "
        f"dependency={violation.dependency} "
        f"version={violation.version} "
        f"rule={violation.rule} "
        f"message={violation.message}"
    )


def scan(root, config, mode, selected_paths=None):
    violations = []
    paths = find_selected_files(root, selected_paths) if selected_paths else find_files(root)
    for path in paths:
        if path.name == "pom.xml":
            violations.extend(scan_pom(path, config, mode))
        elif path.name == "go.mod":
            violations.extend(scan_go_mod(path, config, mode))
    return violations


def parse_args(argv):
    parser = argparse.ArgumentParser(description="Scan contract dependency versions.")
    parser.add_argument("--mode", choices=["master", "rc", "formal-only", "rc-or-formal"], required=True)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--config", type=Path, default=Path("config/contract-dependencies.json"))
    parser.add_argument("--path", action="append", default=[], type=Path, help="Changed file to scan. go.sum maps to sibling go.mod. May be repeated.")
    return parser.parse_args(argv)


def main(argv):
    args = parse_args(argv)
    root = args.root.resolve()
    config = load_config(args.config.resolve())
    violations = scan(root, config, args.mode, args.path)
    if violations:
        print(f"Contract dependency violations found: {len(violations)}")
        for violation in violations:
            print(format_violation(violation, root))
        return 1
    print("No contract dependency violations found.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
