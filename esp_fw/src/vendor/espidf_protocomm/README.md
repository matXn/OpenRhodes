# ESP-IDF protocomm BLE transport patch

Source: https://github.com/espressif/esp-idf/tree/v4.4.7/components/protocomm
Apache-2.0; original notices and LICENSE retained. This source overrides the
matching precompiled transport in Arduino ESP32 2.0.17. The private headers are
from that same release; update them together when changing the pinned platform.

OpenRhodes changes (2026-10-24):
- ATT reads fetch the current attribute and use the request offset. The upstream
  callback retains a static remaining length/pointer, including after disconnect.
  An interrupted 22-byte read of our 53-byte version response leaves 31 bytes;
  the next connection then receives only the first 31 bytes of the new response.
- Increase the characteristic capacity from 257 to 512 bytes, matching the
  existing encrypted paginated scan response limit.

No Security 1 handshake or encryption implementation is changed. The remaining
protocomm and simple_ble implementations come from the pinned SDK.
Run test/run-ble-read.cmd to exercise the actual read callback with mocked ATT.
