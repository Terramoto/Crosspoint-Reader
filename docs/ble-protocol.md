# CrossPoint companion protocol

Protocol version 6 uses Bluetooth LE for pairing, bidirectional annotation synchronization, low-power phone-notification
delivery, and negotiation of a short-lived Wi-Fi transfer session. EPUB files are never sent over BLE.

## Bluetooth service

| Purpose | UUID | Direction | Properties |
| --- | --- | --- | --- |
| Service | `7b1f0001-6f70-4f69-8f31-63726f737370` | — | — |
| Control | `7b1f0002-6f70-4f69-8f31-63726f737370` | Phone to reader | Write, encrypted |
| Event | `7b1f0003-6f70-4f69-8f31-63726f737370` | Reader to phone | Notify, encrypted |

Control and event values are complete UTF-8 JSON objects of at most 512 bytes. The reader is the GATT peripheral and
displays the six-digit passkey used to create an encrypted bond. Once notifications are enabled, the phone writes
`hello {version:6,pendingActions,notificationPollMinutes}`. This is the ready barrier; the reader sends no sync events before it.
Android negotiates a 517-byte MTU before service discovery. Until an exchange completes, the reader retries the
current idempotent barrier or annotation every 1.5 seconds.

## Sleep notification polling

Notification polling is off by default. The companion app lets the user enable it and choose a 5, 10, 15, or 30 minute
interval. Android Notification Access must also be granted explicitly. The interval sent in `hello` is persisted by the
reader and armed as an ESP32-C3 deep-sleep timer whenever the reader sleeps.

The additional **1m test** selection is an isolated, one-shot RTC timer diagnostic. After one minute the reader renders
`Timer wake successful` without starting Bluetooth, then sleeps again with only the Power-button wake source armed.
Wake details are stored in `/.crosspoint/phone-notifications-diagnostics.json`, including the last wake cause, requested
timer duration, ESP-IDF timer-arm result, total wake count, and timer-wake count. Wake-source arming errors are also
written to the firmware log.

On a timer wake the reader advertises for at most 30 seconds. After the bonded phone sends `hello`, the reader emits
`notification_poll {limit}`. The phone takes a bounded snapshot of active, non-ongoing notifications and replies with
`notifications_begin {count}`. The reader requests each record with `notification_next {index}`. Metadata arrives in
`notification_begin`; UTF-8 title and body data use request/response `notification_chunk` and `notification_data`
messages with hexadecimal chunks. The reader accepts at most five records, 240 title bytes and 600 body bytes per
record, then returns `notifications_ack`.

The reader compares this snapshot with the copy on storage. The e-ink display refreshes only when the snapshot changed,
and remains visible after the ESP32-C3 returns to deep sleep. If the phone is unavailable or delivery stalls, the
reader leaves the existing screen untouched and sleeps again. Pressing Power during the polling window aborts it and
continues into a normal interactive boot.

The Android service stores the bonded reader address and keeps an `autoConnect` GATT request pending while the reader
is asleep. A low-power, service-UUID-filtered `PendingIntent` scan runs as a recovery path; Android can deliver that
result while the screen is locked. Seeing an advertisement starts a three-second stale-link watchdog, after which the
app recreates the automatic GATT request if Android has not connected it. Initial pairing continues to use the visible
callback scan and six-digit reader passkey.

## Annotation synchronization

Closing an EPUB starts a best-effort BLE session for that book. The reader writes annotations to its append-only store
first, so closing remains safe if the phone is unavailable. The explicit **File Transfer > Companion Sync** action can
also replay pending data and process queued books. **Sync Highlights** in the open-book menu performs annotation-only
synchronization and returns to the book; close-time synchronization also remains annotation-only.

The reader emits `sync_begin {bookPath}`. The phone first sends any durable deletion tombstones as
`annotation_delete {bookPath,id}`, one at a time, and waits for `annotation_delete_ack`. It then replays active
phone highlights for installed books with `annotation_put_begin`, chunked `annotation_put_quote` messages, and an
`annotation_put_ack` barrier. Existing IDs and reader tombstones are idempotent and are never resurrected. The phone
then sends `phone_changes_complete`.

The reader sends each record with `annotation_begin`, containing:

- `bookPath`, stable annotation `id`, EPUB revision and spine href
- start and end block/UTF-8 offsets, plus the reader-layout chapter page and starting line when available
- monotonic device sequence and deletion state
- quote byte length

The phone requests quote chunks with `annotation_next {id,offset}`. The reader responds with
`annotation_quote {id,offset,hex,done}`; hex keeps UTF-8 boundaries independent of BLE packets. After the record is
stored, the phone sends `annotation_ack {id,sequence}`. Replays are idempotent by ID and sequence. The reader ends the
exchange with `sync_complete`. Android refreshes its durable queue and responds with either `wifi_request` or
`wifi_skip`; the reader waits for that decision before ending the session.

Phone notes stay in the companion database. Reader annotations live under `/.crosspoint/annotations`, separately from
disposable EPUB layout caches.

## Wi-Fi book session

If the refreshed queue is non-empty, the phone sends `wifi_request` after `sync_complete`. The reader creates a WPA2
access point with a random 24-character password and a random 32-character authorization token. It returns
`wifi_offer {ssid,password,ip,port,token}` over the encrypted BLE link.

Android connects with a per-app `WifiNetworkSpecifier` and sends `wifi_connected`. The reader then stops BLE and starts
its existing HTTP file server. Every companion file request must carry `X-CrossPoint-Token`; WebDAV, WebSocket upload,
and UDP discovery are disabled in this mode. The app:

1. calls `POST /api/companion/ready`;
2. ensures `/Books` exists and reconciles its managed inventory through `GET /api/files?path=/Books`;
3. streams queued additions to `POST /upload?path=/Books` and removals to `POST /delete`;
4. calls `POST /api/companion/complete`.

The reader immediately stops the server and hotspot after completion. The password and token are generated again for
each session. Android may show a system confirmation when joining this local network; the session does not replace or
bind the phone's normal internet network.

Transfers currently restart an individual failed upload on the next session rather than resuming a partial file.
Completed database actions are committed one by one, so the remaining queue survives a disconnect.
After a successful book transfer, the reader returns to BLE once within the same session so highlights that became
eligible when the EPUB was installed can be restored immediately.

## Limits and recovery

- Reader-created quotes are limited to 8192 UTF-8 bytes and one EPUB spine item.
- BLE callbacks only enqueue fixed-size packets; filesystem work runs in the foreground activity loop.
- Close-time sync gives up quickly when the phone is absent and has a bounded synchronization timeout.
- Only one phone bond is retained. Repair pairing by forgetting the reader on Android and clearing the reader bond.
- The original `0x640000` OTA application slots are retained; protocol version 6 needs no enlarged partition table.
