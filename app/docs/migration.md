# Upgrade and data migration

1. Keep the same application ID and signing key. Do not uninstall the existing
   application before upgrading: uninstallation removes its private database.
2. On first launch, read `shelly.db` with SQLite `OPEN_READONLY`. Only RHODESLIGHT
   records are imported; unsupported records remain in the unchanged old file.
3. Preserve root IDs, names, alias IDs, full MACs and address history. Existing
   alias chains are resolved; equal full identities combine. Mixed-identity or
   cyclic old groups stop import with an explicit error rather than discarding
   records.
4. Save the new format as `files/rhodes-catalog.json` using Android AtomicFile.
   Publish the in-memory catalog only after the durable write succeeds. A corrupt
   existing file produces an error, not an empty-list reset.
5. Unknown legacy identities are readable but require explicit confirmation in
   device information before control is enabled. A shared AP address or a reused
   DHCP address is not sufficient to merge two records.
6. Existing receiver/configuration class names remain registered. The old
   `selectedDeviceId` in `files/datastore/appWidget-N.preferences_pb` is imported
   into the new `rhodes-widgets` preferences once per widget. The Glance path was
   checked against the installed AndroidX library. Import reads the preference
   store without changing its selection. Alias lookup resolves old IDs.

The old multi-device widget provider is retained as a compact single-lamp
compatibility provider. It may need a lamp selection because the old aggregate
widget did not have a single selected-device key. Launcher placement dimensions
remain under launcher control.

No destructive database migration, credential deletion, widget deletion or
uninstallation is performed. Reinstalling the older version may require adb's
explicit downgrade flag because this build increments versionCode. The new
catalog is not reverse-synchronized to the old database; preserve the new JSON
before using a fallback for an extended period.

## Physical verification still required

- Upgrade a copy of the existing installation and verify all saved names,
  addresses, aliases and widget selections.
- Check pinning and configuration on the user's launcher.
- Change Wi-Fi networks and verify HTTP-confirmed full-MAC rediscovery.
- Confirm the native card and controls at different font scales.
