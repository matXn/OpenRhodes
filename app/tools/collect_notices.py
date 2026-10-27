"""Collect actual resolved runtime notices; run :app:runtimeInventory first."""
from pathlib import Path
from io import BytesIO
import hashlib
import json
import xml.etree.ElementTree as ET
from zipfile import ZipFile, BadZipFile

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
inventory = []
texts = {}

def pom_licenses(group, module, version, visited=None):
    visited = set() if visited is None else visited
    key = (group, module, version)
    if key in visited:
        return []
    visited.add(key)
    for pom in (CACHE / group / module / version).rglob("*.pom"):
        document = ET.parse(pom)
        found = [{"name": entry.findtext("m:name", namespaces=ns),
                  "url": entry.findtext("m:url", namespaces=ns)}
                 for entry in document.findall("m:licenses/m:license", ns)]
        if found:
            return found
        parent = document.find("m:parent", ns)
        if parent is not None:
            return pom_licenses(*(parent.findtext(f"m:{field}", namespaces=ns)
                                  for field in ("groupId", "artifactId", "version")), visited)
    return []

def collect(archive, artifact):
    for name in archive.namelist():
        leaf = name.rsplit("/", 1)[-1].upper()
        if leaf.startswith(("LICENSE", "NOTICE", "COPYRIGHT")) and not name.endswith("/"):
            content = archive.read(name).decode("utf-8", errors="replace").strip()
            if content:
                key = hashlib.sha256(content.encode()).hexdigest()
                record = texts.setdefault(key, {"sources": [], "text": content})
                record["sources"].append(f"{artifact}: {name}")
    if "classes.jar" in archive.namelist():
        with ZipFile(BytesIO(archive.read("classes.jar"))) as classes:
            collect(classes, artifact)

for line in (ROOT / "app/build/runtime-artifacts.tsv").read_text().splitlines():
    identity, filename = line.split("\t", 1)
    group, module, version = identity.split(":")
    directory = CACHE / group / module / version
    licenses = pom_licenses(group, module, version)
    inventory.append({"artifact": identity, "licenses": licenses})
    try:
        with ZipFile(filename) as archive:
            collect(archive, identity)
    except BadZipFile:
        raise RuntimeError(f"Not a ZIP artifact: {identity}")

asset = ROOT / "app/src/main/assets/third-party-notices.txt"
asset.parent.mkdir(parents=True, exist_ok=True)
overview = ["OpenRhodes third-party runtime components", "",
            "Generated from the resolved debug runtime artifacts. Component copyrights",
            "and licenses remain with their respective authors. Test-only tools are not",
            "packaged in the application. The Gradle wrapper is Apache-2.0 licensed.", ""]
for row in inventory:
    overview.append(row["artifact"])
    for license_info in row["licenses"]:
        overview.append(f"  {license_info['name']} — {license_info['url']}")
overview += ["", "Bundled license and notice texts", ""]
for record in texts.values():
    overview += ["\n" + "=" * 72, *sorted(set(record["sources"])), "", record["text"]]
# 字标由用户原图描摹；重新收集许可时保留本地来源及界面字体许可。
wordmark_license = ROOT / "app/src/main/assets/fonts/polymath-demo-notice.txt"
if wordmark_license.exists():
    overview += ["", "Open Rhodes 字标：Polymath Demo Bold 原始轮廓，本地实验构建，无嵌入字体文件。", "", wordmark_license.read_text(encoding="utf-8")]
for family in ("manrope", "jost", "outfit"):
    license_file = ROOT / f"app/src/main/assets/fonts/{family}-ofl.txt"
    if license_file.exists():
        overview += ["", f"Interface font: {family}",
                     f"https://github.com/google/fonts/tree/main/ofl/{family}", "",
                     license_file.read_text(encoding="utf-8")]
asset.write_text("\n".join(overview) + "\n", encoding="utf-8")
docs = ROOT / "docs/runtime-dependencies.json"
docs.write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"{len(inventory)} runtime artifacts; {len(texts)} distinct bundled notice texts")
missing = [row["artifact"] for row in inventory if not row["licenses"]]
print("Missing POM license metadata:", missing)
