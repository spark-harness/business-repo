#!/usr/bin/env python3
import runpy
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "tooling" / "contract-dependency-scan" / "contract_dependency_scan.py"

runpy.run_path(str(SCRIPT), run_name="__main__")
