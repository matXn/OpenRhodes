# Packaging verification — 2026-10-27

Source baseline: current local OpenRhodes-breath-wifi workspace, including its
uncommitted BLE/Wi-Fi/startup improvements, packaged into esp_fw.
Runtime src/ and web/ files were compared byte-for-byte with that baseline.
No hardware was flashed or reset for this packaging task.

- Clean-directory PlatformIO build passed with automatic web generation.
- RAM: 70,676 / 327,680 bytes; flash: 1,737,521 / 1,966,080 bytes.
- JavaScript syntax checks passed for web/control.js and web/setup.js.
- Existing MSVC host tests passed: startup fade/calibration, Wi-Fi setup and
  visible-first priorities, AP visibility/NVS rollback, BLE request frames,
  interrupted/reconnected/long/repeated ATT reads.
- Browser integration tests and physical radio/touch tests were not repeated;
  runtime firmware and web source were not changed by this reorganization.
- Generated embedded headers, host harnesses, preview output, .pio artifacts,
  local device logs and PCB/manufacturing files are excluded from Git.
- Vendored Espressif transport license and change notices are retained.

The original root-level firmware tree remains as a historical baseline. Current
firmware development/build instructions are in esp_fw/README.md.
