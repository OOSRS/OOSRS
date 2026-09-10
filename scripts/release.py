#!/usr/bin/env python3
"""Collect the built public distribution and its launcher update metadata."""
import argparse
import hashlib
from pathlib import Path
import re
import shutil

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--check-tag")
args = parser.parse_args()
version = re.search(r'version = "([0-9.]+)"', (root / "build.gradle.kts").read_text()).group(1)
if args.check_tag:
    if args.check_tag != "v" + version:
        raise SystemExit("Release tag does not match the built source version")
    raise SystemExit(0)
out = root / "release-output"
out.mkdir(exist_ok=True)
for old in out.iterdir():
    if old.is_file():
        old.unlink()
client = root / f"runelite-client/build/libs/openosrs-client-{version}.jar"
shutil.copy2(client, out / client.name)
for module in ("openosrs-api", "runelite-api"):
    for file in (root / module / "build/libs").glob(f"*-{version}*.jar"):
        shutil.copy2(file, out / file.name)
docs = root / f"build/distributions/openosrs-javadocs-{version}.zip"
shutil.copy2(docs, out / docs.name)
revision = re.search(r"^revision=(\d+)$", (root / "gamepack.properties").read_text(), re.M).group(1)
digest = hashlib.sha256(client.read_bytes()).hexdigest()
(out / "update.properties").write_text(f"version={version}\nasset={client.name}\nsha256={digest}\njava=21\nrevision={revision}\n")
(out / "SHA256SUMS").write_text("".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n" for p in sorted(out.iterdir()) if p.is_file()))
print(f"Prepared OpenOSRS {version}: {out}")
