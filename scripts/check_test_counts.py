#!/usr/bin/env python3
"""Require actual JUnit 4 and JUnit 5 executions, with no skipped failures."""
from pathlib import Path
import xml.etree.ElementTree as ET

for module in ("runelite-client", "openosrs-api"):
    reports = list(Path(module, "build/test-results/test").glob("TEST-*.xml"))
    tests = failures = errors = 0
    for report in reports:
        root = ET.parse(report).getroot()
        tests += int(root.attrib["tests"]) - int(root.attrib.get("skipped", 0))
        failures += int(root.attrib["failures"])
        errors += int(root.attrib["errors"])
    print(f"{module}: tests={tests}, failures={failures}, errors={errors}")
    if tests == 0 or failures or errors:
        raise SystemExit(f"JUnit gate failed for {module}")
