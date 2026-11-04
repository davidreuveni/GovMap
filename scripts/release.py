#!/usr/bin/env python3
"""Verify an existing signed APK; prepare or publish its public GitHub release."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CONFIG = (ROOT / "src/il/david/govmap/dark/UpdateRelease.java").read_text()
REPO = re.search(r'REPOSITORY = "([^"]+)"', CONFIG).group(1)
PACKAGE = re.search(r'PACKAGE = "([^"]+)"', CONFIG).group(1)
SIGNER = re.search(r'CERTIFICATE = "([^"]+)"', CONFIG).group(1)


def run(args):
    return subprocess.run([str(a) for a in args], check=True, text=True, capture_output=True).stdout


def prepare(apk, sdk, output):
    tools = sdk / "build-tools/35.0.0"
    verification = run([tools / "apksigner", "verify", "--verbose", "--print-certs", apk])
    certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", verification)
    if [x.lower() for x in certs] != [SIGNER]:
        raise ValueError("APK must have the original GovMap signing certificate")
    badging = run([tools / "aapt", "dump", "badging", apk])
    def field(pattern):
        result = re.search(pattern, badging)
        if not result:
            raise ValueError("Missing APK identity field")
        return result.group(1)
    package = field(r"^package: name='([^']+)'" )
    code = int(field(r"^package: .*?versionCode='([0-9]+)'"))
    version = field(r"^package: .*?versionName='([^']+)'" )
    minimum = int(field(r"(?m)^sdkVersion:'([0-9]+)'"))
    if package != PACKAGE or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) or not 0 < code <= 2147483647:
        raise ValueError("Wrong GovMap package/version")
    size = apk.stat().st_size
    if minimum < 26 or not 0 < size <= 32 * 1024 * 1024:
        raise ValueError("APK outside updater limits")
    metadata = dict(formatVersion=1, packageName=package, versionCode=code, versionName=version,
                    minSdk=minimum, size=size, sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                    apkUrl=f"https://github.com/{REPO}/releases/download/v{version}/GovMap-Dark.apk")
    output.mkdir(parents=True, exist_ok=True)
    dest = output / "GovMap-Dark.apk"
    if apk.resolve() != dest.resolve():
        shutil.copyfile(apk, dest)
    (output / "update.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (output / "release-notes.md").write_text(
        f"GovMap {version} (version code {code})\n\n"
        "Install over the existing GovMap app. Keep application data.\n"
        "Enable automatic updates when asked. Long-press GovMap for the developer menu.\n\n"
        "Developer tools: speed report, save log, location status, cache overview, reload test, connection check, copy diagnostics, fresh log session and detailed update status.\n\n"
        "Diagnostics export only on explicit action. Android may require installation approval.\n")
    return metadata


def publish(metadata, output):
    repo = json.loads(run(["gh", "repo", "view", REPO, "--json", "visibility"]))
    if repo["visibility"] != "PUBLIC":
        raise ValueError("Release repository must be public; no GitHub tokens are stored in phones")
    count = int(run(["gh", "api", f"repos/{REPO}/releases?per_page=100", "--jq",
                     "map(select(.draft == false and .prerelease == false)) | length"]).strip())
    if count:
        with tempfile.TemporaryDirectory(prefix="govmap-latest-") as tmp:
            run(["gh", "release", "download", "--repo", REPO, "--pattern", "update.json", "--dir", tmp])
            previous = json.loads((Path(tmp) / "update.json").read_text())
        if previous.get("packageName") != PACKAGE or metadata["versionCode"] <= previous["versionCode"]:
            raise ValueError("New version code must exceed the currently published release")
    tag = "v" + metadata["versionName"]
    # gh uploads all assets in a draft before publication. Never edit a published release in place.
    print(run(["gh", "release", "create", tag, "--repo", REPO, "--title", "GovMap " + metadata["versionName"],
               "--notes-file", output / "release-notes.md", "--latest",
               output / "GovMap-Dark.apk", output / "update.json"]), end="")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "publish"])
    parser.add_argument("--apk", type=Path, default=ROOT.parent / "GovMap-Dark.apk")
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("GOVMAP_SDK", os.environ.get("ANDROID_HOME", ""))))
    parser.add_argument("--out", type=Path, default=ROOT / "release")
    args = parser.parse_args()
    metadata = prepare(args.apk.resolve(), args.sdk.resolve(), args.out.resolve())
    print(f"Verified GovMap {metadata['versionName']} ({metadata['versionCode']}); release files: {args.out}")
    if args.action == "publish":
        publish(metadata, args.out.resolve())


if __name__ == "__main__":
    main()
