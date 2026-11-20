"""直接从用户 PNG 整体描摹，不再使用字体或逐字排版。"""
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).with_name('trace_wordmark.py')), run_name='__main__')
