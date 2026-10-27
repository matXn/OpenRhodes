# Bluetooth Wi-Fi setup

Code-free setup requires OpenRhodes 2.1.3-dev and the matching ESP32-S3 firmware.
The existing AP setup, hidden-SSID switch and HTTP controls remain available.
Bluetooth provisions Wi-Fi; normal dimming still uses the local Wi-Fi network.

## Use

1. For a lamp already in the catalog, open Wi-Fi settings and choose Bluetooth
   setup. The app checks the lamp's full station MAC over HTTP and opens a
   five-minute Bluetooth window. Old firmware's proof is read automatically.
2. For a new lamp, choose Bluetooth setup from Add device. Updated firmware
   does not require a setup code. For old firmware only, connect to the lamp AP
   and use the compatibility action to read its proof without typing it.
3. Grant nearby-device permission, enable Bluetooth and search for the lamp.
   On Android 11 and earlier, BLE scanning requires location permission and the
   system location switch. This implementation does not obtain coordinates.
4. Connect, scan nearby Wi-Fi and select an SSID. Enter its password, or select
   saved-password reuse for a network already stored on the lamp. Hidden SSIDs
   can be entered manually. Select "连接并优先保存".
5. Wait for the lamp to confirm Wi-Fi connection and saved credentials. Its
   returned full MAC merges with the existing catalog identity, preserving
   endpoint history and widget bindings. The successfully selected network
   becomes first priority. Phone and lamp need the same LAN for normal control.

The lamp advertises as RhodesLight- followed by its MAC suffix. BLE is available
for five minutes after startup and the window renews after secured requests.
An existing HTTP connection can reopen it. The AP remains available afterward.

## Protocol and dependency boundary

Native Android GATT talks to the firmware's protocomm Security 1 transport.
X25519 uses Tink; SHA-256 and AES-CTR use platform crypto providers. No Espressif
Android application SDK, cloud account, camera or Google Play service is needed.
The protobuf session framing follows Espressif Security 1. It never falls back
to Security 0. Code-free mode must be explicitly advertised as
`pop_required: false`; missing/invalid flags retain the legacy proof requirement.

Wi-Fi credentials travel inside the encrypted session. Code-free mode omits
the optional proof-of-possession step, so it does not authenticate an owner or
prevent an active nearby impersonator. Nearby devices can initiate setup while
the five-minute window is open. The legacy HTTP proof lookup uses the existing
local control header, not authenticated HTTPS. No proof is derived from a public
MAC, hardcoded globally, or sent as a new plaintext credential exchange.
The app keeps entered Wi-Fi passwords in screen memory, clears them after a
submission and does not add them to its catalog. There is no code input or copy
control in the new setup UI.

Commands share one GATT transaction lock and one continuous Security 1 stream.
Transport errors close that session; unconfirmed requests are never replayed.
Radio changes and saved-profile writes run through the existing firmware main
loop, preserving AP availability and priority/failure handling.

## Simplified app flow (2.1.4-dev)

The single add-device screen automatically searches LAN and BLE. Full verified
station MACs combine advertisements into one candidate; names or BLE addresses
alone never cause a merge. Reachable LAN lamps enroll/open with one tap. New BLE
lamps connect and scan automatically, then ask for a network and, only when needed,
a password. Saved/open networks connect directly on selection. Successful setup
saves the identity and waits for actual local reachability before opening controls.
IP entry, AP recovery and old-firmware compatibility stay in expandable fallback
sections. No manual code-entry field was reintroduced.
