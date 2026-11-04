#!/usr/bin/env python3
"""Set a release version without shell interpolation of workflow inputs."""
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET

version, code = os.environ["GOVMAP_VERSION"], os.environ["GOVMAP_VERSION_CODE"]
if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) or not code.isdecimal() or not 0 < int(code) <= 2147483647:
    raise SystemExit("Invalid release version")
path = Path(__file__).resolve().parents[1] / "AndroidManifest.xml"
namespace = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", namespace)
tree = ET.parse(path)
if int(code) < int(tree.getroot().get("{" + namespace + "}versionCode")):
    raise SystemExit("Version code cannot decrease")
tree.getroot().set("{" + namespace + "}versionName", version)
tree.getroot().set("{" + namespace + "}versionCode", str(int(code)))
ET.indent(tree, space="    ")
tree.write(path, encoding="unicode")
