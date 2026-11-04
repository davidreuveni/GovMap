"""Exercise release preparation against the real signed APK and a tampered copy."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("govmap_release", root / "scripts/release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
apk = root.parent / "GovMap-Dark.apk"
sdk = Path(os.environ["GOVMAP_SDK"])
with tempfile.TemporaryDirectory(prefix="govmap-publish-test-") as tmp:
    folder = Path(tmp)
    meta = release.prepare(apk, sdk, folder / "public")
    assert meta["packageName"] == "il.david.govmap.dark"
    assert meta["sha256"] == hashlib.sha256(apk.read_bytes()).hexdigest()
    assert meta["size"] == apk.stat().st_size
    assert meta["apkUrl"].endswith(f"/releases/download/v{meta['versionName']}/GovMap-Dark.apk")
    assert sorted(x.name for x in (folder / "public").iterdir()) == ["GovMap-Dark.apk", "release-notes.md", "update.json"]
    tampered = folder / "tampered.apk"
    with zipfile.ZipFile(apk) as src, zipfile.ZipFile(tampered, "w") as dest:
        for item in src.infolist():
            body = src.read(item.filename)
            if item.filename == "classes.dex":
                body = body[:-1] + bytes([body[-1] ^ 1])
            dest.writestr(item, body)
    try:
        release.prepare(tampered, sdk, folder / "rejected")
    except (subprocess.CalledProcessError, ValueError):
        pass
    else:
        raise AssertionError("Tampered APK accepted for publishing")
    assert not (folder / "rejected").exists()
manifest = ET.parse(root / "AndroidManifest.xml").getroot()
a = "{http://schemas.android.com/apk/res/android}"
assert manifest.get("package") == meta["packageName"]
assert manifest.get(a + "versionName") == meta["versionName"]
assert int(manifest.get(a + "versionCode")) == meta["versionCode"]
components = {x.tag: x for x in manifest.find("application") if x.tag != "activity"}
assert components["receiver"].get(a + "exported") == "false"
assert components["service"].get(a + "permission") == "android.permission.BIND_JOB_SERVICE"
print("Publisher checks passed: original signature, real APK metadata, tamper rejection, public assets, component permissions")
