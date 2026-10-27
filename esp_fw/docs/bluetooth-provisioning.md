# BLE Wi-Fi provisioning

ESP32-S3 firmware adds BLE provisioning alongside the existing AP/HTTP setup.
Startup fade, touch calibration, saved Wi-Fi priorities and AP visibility are
unchanged. BLE starts after initial lamp startup, not before first light.

## Lifetime and code-free setup (2026-10-24)

- Advertised name: `RhodesLight-` plus the last six station-MAC digits.
- Startup window: at least five minutes. Each secured request renews the window.
- With no successfully saved Wi-Fi profile, BLE stays available after the window.
  Once a profile is saved, expiry stops the BLE transport; AP and station Wi-Fi
  remain available. `expires_ms: -1` means BLE is waiting for first setup.
- Explicit `POST /api/wifi/ble` reopens BLE. GET reads its status. Both require
  `X-Rhodes-Control: 1` and return `ready`, `name`, `pop_required: false`, `expires_ms` with
  `Cache-Control: no-store`.
- Security 1 uses a null optional proof, so the updated app connects without
  asking for a setup code. Existing `rhodes-net/ble-pop` NVS data is left intact
  but is no longer used or exposed. Local HTTP retains its control-header guard.
- This keeps encryption but removes owner proof. Nearby devices can provision
  during the open window; it does not prevent an active impersonator. No fixed
  global code or MAC-derived secret substitutes for the removed proof.

AP provisioning is retained even after a successful BLE connection. The same
eight-profile store is used; a successfully selected network becomes priority
one. Failed attempts do not replace saved credentials.

## GATT and session

Service UUID: `95e7b36c-43f7-4ad0-9e6f-104b0cb54e10`.

| Endpoint | Characteristic UUID | Purpose |
| --- | --- | --- |
| prov-session | 95e7ff51-43f7-4ad0-9e6f-104b0cb54e10 | Espressif Security 1 |
| proto-ver | 95e7ff52-43f7-4ad0-9e6f-104b0cb54e10 | Write ESP, then read version |
| rhodes-config | 95e7ff53-43f7-4ad0-9e6f-104b0cb54e10 | Encrypted setup commands |

Version response: `{"rhodes":{"ver":1,"sec_ver":1,"pop_required":false}}`.
Security 1 uses X25519 and the standard continuous AES-CTR stream without its
optional SHA-256 proof mixing. There
is no plaintext credential endpoint and no Security 0 fallback. The GATT config,
UUID bytes have static lifetime because protocomm retains
their pointers for asynchronous callbacks.

The optional-proof behavior is implemented by Espressif's
[Security 1 handler](https://github.com/espressif/esp-idf/blob/v4.4.7/components/protocomm/src/security/security1.c#L256).
## Encrypted command frames

All lengths count UTF-8 bytes. Frames are at most 101 bytes; JSON responses are
at most 512 bytes. Responses include `v: 1`, except request-level error objects.
Responses contain no Wi-Fi password.

| Operation | Exact request bytes | Response |
| --- | --- | --- |
| Status | `01` | MAC, status, attempt_id, IP, SSID, error |
| Start scan | `02` | scanning |
| Scan page | `03 offset` | scanning, generation, networks, next |
| New credentials | `04 ssidLen passwordLen ssid password` | accepted, attempt_id |
| Release setup lease | `05` | closed |
| Reuse saved password | `06 ssidLen ssid` | accepted, attempt_id |

SSID length is 1–32 bytes. Password length is zero for an open network, 8–63
bytes for a passphrase, or 64 hexadecimal bytes for a raw key. Embedded NUL,
trailing/truncated payload and invalid lengths are rejected. Scan snapshots
retain up to 24 strongest unique SSIDs, with up to two entries per response and
a size guard for escaped SSIDs. `next: -1` ends pagination; `generation` detects
replacement scans. Entries expose SSID, RSSI, secure and saved flags.

Status values are ready, connecting, connected and failed. The client follows
the accepted attempt_id; connection success is reported after the existing
network pipeline confirms Wi-Fi and persistence. The full station MAC is the
stable identity across SSIDs. BLE callbacks queue bounded requests; the main
loop performs all radio/profile operations through the existing setup-priority
lease, scan and connection state machines.

## Interrupted BLE reads fixed (2026-10-24)

The ESP-IDF 4.4.7 transport kept read_len/read_buf as static state across ATT
reads and disconnects. An interrupted 22-byte read of the 53-byte version left
31 bytes; a fresh connection could receive only the first 31 bytes of the next
version, producing the Android trailing-comma parser error. The original callback
fails the new regression harness; the stateless offset-based callback passes.

The locally vendored Apache-2.0 transport and matching private headers are in
src/vendor/espidf_protocomm. Firmware map verification confirms the new transport
is linked. Characteristic capacity is also raised from 257 to 512 bytes to match
existing paginated encrypted responses. Security 1 remains unchanged.

## Unprovisioned BLE availability (2026-10-24)

The BLE setup service now remains active beyond five minutes while the persistent
Wi-Fi profile list is empty. Profiles are normally saved only after a connection
succeeds; a legacy saved profile is migrated at startup. A successful setup does
not shorten the first five minutes. Secured BLE requests still renew the window.
Status reports `expires_ms: -1` during this first-setup wait. The AP stays on.
