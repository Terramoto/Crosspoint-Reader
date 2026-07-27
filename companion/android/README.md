# CrossPoint Companion for Android

The native Kotlin/Compose companion imports EPUBs, manages which books should be installed on the reader, receives
reader highlights over Bluetooth, and stores phone-only notes.

Requirements: Android Studio, JDK 17, and Android SDK 36. Open this directory as the Gradle project. The wrapper is
pinned to Gradle 8.13 for Android Gradle Plugin 8.13; in Android Studio select its bundled JDK under **Gradle JDK**.

Install the app and grant Bluetooth/Nearby Devices permissions. On the reader choose
**File Transfer > Companion Sync**, start the companion service, and enter the displayed six-digit code in Android's
pairing dialog. The foreground service keeps reconnecting while the phone screen is off.

## Device library

Import EPUBs through Android's Storage Access Framework, then mark each book **Add to reader** or
**Remove from reader**. These desired-state changes remain queued.

At the next companion sync, BLE synchronizes highlights and securely passes one-time Wi-Fi session credentials. The
reader creates a temporary WPA2 hotspot and the app streams queued books to the normal `/Books` directory. Android 10
or newer is required for this Wi-Fi step and may show a system network approval prompt. The hotspot is closed as soon
as the queue completes; the phone's normal internet connection is not globally replaced.

Closing a book starts a short, best-effort highlight-only BLE sync. It does not start Wi-Fi unless book actions are
already queued. If the phone is absent, the reader retains its annotations and replays them at a later sync.

## Highlight backups

After importing an EPUB, Android asks for its containing folder. This one-time grant is required because a
single-file Storage Access Framework permission cannot create sibling files. Every changed book is exported beside
its associated EPUB after a short debounce as:

- Markdown (`*.highlights.md`) for readable quotes and notes.
- JSON (`*.highlights.json`) for stable IDs, EPUB positions, timestamps, revisions, and deletion tombstones.

Exports use the original EPUB filename plus the last eight characters of its library ID. Each book can change its own
folder from its details screen, and its **Export** button schedules an immediate export. If that folder is unavailable,
Room remains authoritative and the export stays pending. Phone-side deletions also remain pending until acknowledged
by the reader.

When a folder is selected, the app also searches for an existing matching `*.highlights.json` backup and restores its
highlights and notes. Imported app records can be removed with **Delete app record**; this does not silently delete a
copy already installed on the reader.

## Resetting a pairing

Do not erase or fully flash the reader to repair a bond. Forget **CrossPoint Reader** in Android Bluetooth settings,
open the reader's companion sync screen, and hold the lower side button for 1.5 seconds. Both sides must forget the
stale key.

If an Android vendor suppresses the local-network approval prompt, the app shows the temporary SSID/password and an
**Open Wi-Fi settings** fallback while the reader hotspot is active.
