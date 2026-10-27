# Hidden setup hotspot

The setup AP continues serving DHCP, captive DNS and HTTP while its SSID is
hidden. The default remains visible. `rhodes-net/ap-hidden` persists the choice
across reboots; both startup and the AP recovery path reuse it. This does not
change saved station networks, their priorities, the AP password or LAN control.

`GET /api/state` advertises `ap_hidden` but never includes credentials.

`GET /api/wifi/ap` returns `{ssid,password,hidden,ip}` for the active setup AP.
`POST /api/wifi/ap` with form `hidden=1` or `hidden=0` changes visibility and
returns the same confirmed configuration. Both methods require the existing
`X-Rhodes-Control: 1` header and return `Cache-Control: no-store, max-age=0`.
This header is the existing explicit-request check, not user authentication.
Saved home/router passwords are not exposed by this endpoint.

Changing visibility edits only `ssid_hidden` in the current AP configuration,
preserving its channel, authentication and clients configuration. Configuration
or persistence failures return an error; a failed save attempts to restore the
previous radio setting. Repeated identical settings avoid additional NVS writes.

The Android client copies SSID/password separately, masks the password until
requested, and marks password clipboard content sensitive. For manual joining,
select a hidden network and WPA2-Personal in the phone's Wi-Fi settings.
