"""Format only this repository's Kotlin sources with a pinned local ktfmt tool."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import urllib.request

root = Path(__file__).resolve().parents[1]
tool = root / "build/tools/ktfmt.jar"
expected = "5b3d5286fd2defcc7dc8e28c21ddf156cc6b2d8682bdcd929ce4333e7a6201f2"
if not tool.exists():
    url = "https://repo.maven.apache.org/maven2/com/facebook/ktfmt/0.64/ktfmt-0.64-with-dependencies.jar"
    with urllib.request.urlopen(url, timeout=60) as response:
        content = response.read()
    if hashlib.sha256(content).hexdigest() != expected:
        raise RuntimeError("Formatter checksum mismatch")
    tool.parent.mkdir(parents=True, exist_ok=True)
    tool.write_bytes(content)
if hashlib.sha256(tool.read_bytes()).hexdigest() != expected:
    raise RuntimeError("Formatter checksum mismatch")
java_home = os.environ.get("JAVA_HOME")
java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
if not java:
    raise RuntimeError("Set JAVA_HOME to JDK 21")
files = list((root / "app/src").rglob("*.kt")) + [root / "build.gradle.kts", root / "settings.gradle.kts", root / "app/build.gradle.kts"]
subprocess.run([java, "-jar", str(tool), "--kotlinlang-style", *(str(path) for path in files)], check=True)
