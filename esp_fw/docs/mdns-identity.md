# Hardware identity and mDNS verification

The ESP32 exposes the full station MAC and `rhodes-<12 hex digits>` identity
through `/api/state`. The AP and station share this logical identity; IP
addresses and Wi-Fi names are not device identities.

mDNS runs in the ESP-IDF task. The application starts it on the AP even before
station association, retries initialization/service failures every two seconds,
and restarts the responder when the station address changes. The HTTP service
uses an instance name containing the MAC suffix, with full `mac`, `device_id`
and `model` TXT entries. The hostname remains `rhodeslight.local`.

Android NSD discovers and resolves services; application status polling uses
HTTP separately. Increasing HTTP polling frequency does not change mDNS query
timing or bypass network multicast filtering.
