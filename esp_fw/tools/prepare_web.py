"""Generate embedded web resources before every PlatformIO build."""
from pathlib import Path
import runpy

Import("env")
runpy.run_path(str(Path(env.subst("$PROJECT_DIR")) / "tools/build_web.py"), run_name="__main__")
