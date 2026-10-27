# Wi-Fi setup priority

Automatic startup/reconnect now first performs one asynchronous all-channel
scan. It tries visible saved SSIDs in saved priority order, using the strongest
observed channel for each SSID. Unseen SSIDs remain fallback candidates for
hidden networks or missed scans. A NO_AP_FOUND event ends an automatic attempt
after at least 1.5 seconds rather than waiting out the full 25-second timeout.
User-selected connection requests retain the existing timeout and take priority.

The scan is shared with the setup page if the user opens it in flight; doing
so suppresses automatic selection. Credentials and saved priority are unchanged.
Scans and association remain serialized, including cancellation-event draining.

The setup page owns foreground radio activity. Opening `/setup` pauses an
unfinished automatic saved-network connection immediately, keeping the AP and
HTTP server running. A healthy existing STA connection is retained for background
scanning. An explicit scan interrupts an unfinished connection; an explicit
network submission cancels any scan and schedules the selected network next.

`POST /api/wifi/setup` with `action=hold` renews a 45-second setup lease. The
visible page renews it every 10 seconds; `action=release` releases it on page
hide/close. A lost page expires naturally. Saved-network retry rounds cannot
start while the lease or a scan owns the radio. An explicit connection attempt
continues while the page heartbeat is running.

Cancellation drains the previous scan completion before the next association or
scan. A transient `ESP_ERR_WIFI_STATE` while the station is disconnecting is
retried for a bounded interval, not reported as a successful empty scan. Last
successful scan results remain cached if a later scan fails.

The page starts scanning automatically when not connected. It allows selecting
a new network while an old one is connecting, invalidates old scan UI work when
submitting a connection, and ignores late scan responses. Failed scans preserve
the previous visible SSID list.

Only a successful connection persists credentials. A manually selected network
is moved to priority 1 on success, including updates to an existing profile;
automatic reconnects preserve the chosen order. The eight-profile capacity and
existing NVS storage format are unchanged.
