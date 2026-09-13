# Train Wi-Fi (Android POC)

Android counterpart of [`wifi_sncf.sh`](../wifi_sncf.sh). It joins the on-board Wi-Fi of French
trains on its own, clears the captive portal without the user touching anything, and shows the
trip (destination, ETA, delay, progress, speed) in a permanent notification.

It is a proof of concept: plain Kotlin, platform APIs, two third-party dependencies
(`kotlinx-coroutines` for `StateFlow`, `osmdroid` for the map). No location permission: the map
shows the train's own GPS from the portal, not the phone's.

## Always on, without running all the time

Between trips nothing runs and nothing is shown. The app wakes up when the phone joins a Wi-Fi
that turns out to be a train:

1. **Wi-Fi watch.** `ConnectivityManager.registerNetworkCallback` with a *PendingIntent*
   (`AutoConnect.arm`). The system fires `WifiWatchReceiver` whenever a Wi-Fi network appears,
   validated or not, even if the app process is dead. No location permission involved.
2. **Probe, then start.** The receiver probes `/connection/status` on both portals over that
   network (3 s connect, 4 s read). At home this is one fast failure and the app goes back to
   sleep. On a train the foreground service starts and the trip card appears.
3. **Self-stop.** The service goes to standby 2 min after the Wi-Fi is gone (long enough for a
   tunnel), or after 10 min on a Wi-Fi with no portal, and re-arms the watch on the way out.
4. **Reboot.** `BootReceiver` (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`) re-arms the watch when
   auto-connect is enabled. Wi-Fi suggestions themselves survive reboots.
5. **Safety net.** A persisted `JobScheduler` job (`WatchJobService`, every 15 min) repeats the
   probe if a Wi-Fi is present and the service is not running, for the case where the portal was
   down at the moment the Wi-Fi connected.

**Battery optimisation exemption.** Android 12+ refuses foreground-service starts from a background
receiver unless the app is exempt from battery optimisation; the exemption also keeps network
access for the probe while the phone dozes in a pocket. The app shows a "Background start" card
with the system dialog until it is granted. Without it, the receiver posts a "Train Wi-Fi detected"
notification instead and a tap on Connect starts the service (user interaction is always allowed).

**Buttons.** *Enable auto-connect* registers the Wi-Fi suggestions, arms the watch, schedules the
job and probes the current Wi-Fi right away. *Disable* (also the notification's Stop action) turns
all of that off; a plain stop would be undone by the watcher within seconds on a train.

Why not the obvious alternatives: manifest receivers for Wi-Fi state changes are blocked since
Android 7/8; JobScheduler's network constraint only fires on *validated* networks, which a captive
portal never is until activated; the network-suggestion post-connection broadcast requires location
permission and, from the background, "Allow all the time".

## How it works

1. **Auto-join.** `WifiManager.addNetworkSuggestions` registers one open-network suggestion per
   SSID in the list (see [`WifiSuggestions.kt`](app/src/main/kotlin/fr/onrails/trainwifi/WifiSuggestions.kt)).
   The user approves the app once, then Android connects by itself whenever an SSID is in range.
2. **Bound network.** A foreground service registers a `NetworkCallback` for `TRANSPORT_WIFI`
   without requiring `NET_CAPABILITY_VALIDATED`. Every portal request goes through
   `Network.openConnection()` on that Wi-Fi `Network`. This is the part a naive port of the shell
   script gets wrong: before activation the Wi-Fi is unvalidated, the default route stays on mobile
   data, and `wifi.sncf` only resolves through the train's DNS.
3. **Detection without location.** The service probes `/router/api/connection/status` on
   `https://wifi.sncf` then `https://wifi.normandie.fr` (4 s connect, 6 s read). Whichever answers
   JSON is the portal. Reading the SSID would need `ACCESS_FINE_LOCATION`, so we don't.
4. **Activation.** If `active` is false it POSTs `/router/api/connection/activate/auto` with the
   headers and body from the shell script, then calls `reportNetworkConnectivity(network, true)`
   so Android revalidates the network instead of deprioritising it.
5. **Polling.** Every 15 s: status, statistics, GPS, train details, bar attendance. Results feed a
   `MutableStateFlow` in [`AppState.kt`](app/src/main/kotlin/fr/onrails/trainwifi/AppState.kt),
   read by the activity and rendered into the notification.

Files, all under `app/src/main/kotlin/fr/onrails/trainwifi/`:

| File | Role |
| --- | --- |
| `AutoConnect.kt` | Arms/disarms the Wi-Fi watch, schedules the safety-net job, probe-then-start logic, battery exemption |
| `WifiWatchReceiver.kt`, `BootReceiver.kt`, `WatchJobService.kt` | Entry points: Wi-Fi available, reboot / update, periodic check |
| `PortalDetector.kt` | Probes both portals on a network; shared by the receiver, the job and the service |
| `TrainWifiService.kt` | Foreground service (`specialUse`), network callback, poll loop, self-stop to standby, demo loop |
| `PortalClient.kt` | `HttpURLConnection` bound to the Wi-Fi `Network`, manual redirects (3 hops) |
| `PortalApi.kt` | The five endpoints plus activation |
| `Parsers.kt` | Tolerant `org.json` parsing of both stop shapes, ISO date handling |
| `Models.kt` | Data classes, `Trip` progress / next stop / destination logic |
| `TripNotification.kt` | Ongoing silent notification with progress bar and `BigTextStyle` |
| `MainActivity.kt` | Hero card (destination, ETA, progress), stat tiles, connection card, buttons, Advanced section |
| `TimelineView.kt` | Canvas-drawn route timeline: passed/upcoming stops, train marker, delays |
| `TrainMap.kt` | osmdroid map: CARTO basemap, train marker from the portal GPS, route through the stops, follow mode |
| `WifiSuggestions.kt`, `Settings.kt` | Suggestions API wrapper, persisted SSID list |
| `DemoData.kt` | Canned JSON for both portals, used by "Demo trip" |

## Build

### GitHub Actions (the intended way)

[`.github/workflows/android.yml`](../.github/workflows/android.yml) runs `./gradlew assembleDebug`
on every push to `android-poc` (and `claude/android-poc-*`) and on manual dispatch, then uploads
the debug APK as the `trainwifi-debug-apk` artifact. Download it from the run's Artifacts section.

### Locally

Requirements: JDK 17 or 21, Android SDK with platform 35 (Android Studio installs it, or set
`ANDROID_HOME` / `local.properties`).

```bash
cd android
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

## Install

```bash
adb install -r app-debug.apk
```

Or copy the APK to the phone and open it (allow installs from unknown sources for the file
manager / browser you use).

## First run: what to approve

1. Open **Train Wi-Fi**, check the SSID list, press **Enable auto-connect**.
2. Press **Allow background start** and accept the system dialog (battery optimisation exemption).
   This is what lets the app wake up on the train weeks later without you opening it.
3. Android 13+: allow **notifications** (the trip info lives there; the service runs even if you
   refuse, but you will not see anything).
4. Android 11+: a system dialog asks to let the app **suggest networks**. Accept. On Android 10 the
   question arrives later as a notification the first time a suggested network is in range.
   If the app was previously refused, `addNetworkSuggestions` returns `APP_DISALLOWED` and the app
   shows where to fix it: Settings > Network & internet > Internet > Network preferences >
   *Apps that can suggest networks* (wording varies by vendor).
5. **Turn off any VPN** (NextDNS, WireGuard, corporate VPN: anything that shows the key icon in
   the status bar), or exclude Train Wi-Fi from it if the VPN app allows it. See below.
6. With a VPN on, the background probe fails silently (EPERM), so auto-start cannot detect the
   train; open the app and press Enable, and the service will tell you the VPN is in the way.

Then put the phone in your pocket. When a listed SSID appears the phone joins it, the service sees
the Wi-Fi network, finds the portal, activates it and the notification switches from
"Waiting for train Wi-Fi" to something like:

```
→ Grenoble 13:18 (+5 min)
91% · 298 km/h · next: Valence TGV 12:40
[progress bar]
  Valence TGV 12:40 (+5 min)
  Grenoble 13:18 (+5 min)
  Data: 106 MB left of 1000 MB, 9.8 MB/s
  Wi-Fi quality 5/5 · 126 devices · bar queue busy
```

**Disable** (button or the notification's Stop action) turns auto-connect off entirely: service,
watch and periodic job. The Wi-Fi suggestions stay registered until you edit the list;
**Save & apply SSIDs** removes all previous suggestions and registers the new list.

## Known limitation: VPNs

Android forbids an app whose traffic is routed through a non-bypassable VPN from using any other
network. `Network.openConnection()` then fails at the socket level with
`Binding socket to network N failed: EPERM`, before a single packet leaves the phone. Since the
whole point of this app is to talk to the portal over the still-captive Wi-Fi while the default
route is elsewhere, it cannot work while such a VPN is active. Going through the VPN is no
alternative: its underlying network is the phone's default one, i.e. mobile data.

The app recognises the EPERM (and an active VPN transport) and shows "A VPN blocks access to the
train Wi-Fi" with the hint, then re-probes every 15 s so it recovers as soon as the VPN is off.
Fixes, by preference: disable the VPN for the trip, exclude the app in the VPN's settings
(only possible if that VPN app offers per-app exclusion or "allow bypass"), or for DNS-filter apps
like NextDNS use Android's *Private DNS* setting instead of the app, which is not a VPN.

## App UI

The screen follows the portal's look: INOUI burgundy accents, navy labels, soft grey rounded
cards, light and dark palettes (`values/colors.xml`, `values-night/colors.xml`), no UI library.

- **Hero card**: destination, ETA pill, "Arrival in N min, on time / +N min", progress bar.
- **Live map** (shown as soon as the portal GPS has a fix): osmdroid with CARTO's free Dark Matter /
  Positron raster basemap following the system theme, a burgundy train marker, and the route as a
  polyline through the stops when their coordinates are present in `/train/details`. The map follows
  the train until you pan; a Recenter chip brings it back. Zoom is capped at 14 to keep tile
  downloads small on the train's quota; tiles are cached on disk. Attribution
  "© OpenStreetMap contributors © CARTO" is drawn on the map, as both licences require.
- **Stat tiles**: train speed, km traveled, km until arrival (sums of the stops' `progress`, metres in the API).
- **Route**: a timeline drawn on canvas, passed stops filled, upcoming stops as rings, the train
  marker on the current segment, delays in burgundy.
- **Connection**: Wi-Fi quality dots, data quota bar, bandwidth, devices, bar queue.
- **Keep on lock screen** switch: posts the card on a default-importance channel with sound and
  vibration off, so it is not filed under "Silent" and stays on the lock screen. Off uses the
  low-importance channel instead. Android still honours the system lock screen setting; if
  "Hide silent notifications" or "Don't show notifications" is on there, the app cannot override it.
- **Advanced** (collapsed by default, state remembered): the SSID editor and the raw log.

## Demo trip

**Demo trip** starts the service with canned responses for both portals (`wifi.sncf` flat stops
with `delay` fields, `wifi.normandie.fr` nested `location` / `arrival` with offset-less dates and a
`null` progress). Stop times are generated relative to now so the "arrival in N min" line and the
timeline look alive. It alternates between the two every 10 s, needs no network at all, and drives
the exact same parsers, screen and notification code as a real trip.

## The log

The activity keeps the last 200 events, including truncated raw JSON of every HTTP response
(newest first). **Copy log** puts the whole thing in the clipboard. When something is off on a
train, paste it into an issue: it is the only way to fix the parsers from inside a train.
The same lines go to logcat under the `System.out` tag (`adb logcat | grep TrainWifi`).

## Unverified on a real train

Nothing in this app has run on board yet. Please confirm, and fix from the app or open an issue:

- **SSIDs.** `_SNCF_WIFI_INOUI`, `_SNCF_WIFI_INTERCITES`, `WIFI_INTERCITES`, `OUIFI`,
  `_WIFI_LYRIA`, `_WIFI_NORMANDIE` come from public sources and are not all verified. The list is
  editable and persisted in the app, so a wrong name can be corrected on board without a rebuild.
  Suggestions are for **open** networks; if a portal uses a passphrase the suggestion will not match.
- **Delay unit.** `delay` on `wifi.sncf` stops is assumed to be **minutes** (as the shell script
  prints it). If it turns out to be seconds the `(+N min)` values will be wrong by a factor of 60.
  When the field is absent (Normandie) the app computes real minus theoretical arrival instead.
- **Activation payload.** `{"without21NetConnection":false}` with `Origin`, `Referer` and a desktop
  Chrome `User-Agent` is what works with curl on `wifi.sncf`. Whether `wifi.normandie.fr` accepts
  the same call, and whether the payload is still current, is unknown. The response is logged raw.
- **Per-stop `progress` semantics.** On board, a stop already passed still had
  `remainingDistance > 0`, so that field does not mean "distance left to this stop". The next stop
  is therefore chosen by time (first ETA still ahead) and `remainingDistance` is only a fallback
  when no stop carries a date. The sums over all stops do match the portal's trip totals, and
  their unit is metres (verified: 315 780 m traveled + 82 053 m left = the portal's 398 km trip).
- **Stop coordinates.** The route polyline needs latitude/longitude per stop. The parser looks for
  `latitude`/`lat` and `longitude`/`lon`/`lng` on the stop, in `coordinates`, `position`, `gps`
  and `location`. Whether wifi.sncf or wifi.normandie.fr actually send coordinates, and under which
  names, is unknown: without them the map shows the train only. The raw `/train/details` line in
  the log tells.
- **Wake-up path.** The PendingIntent network callback, the receiver's probe budget and the
  background foreground-service start with the battery exemption are implemented from the platform
  documentation and have not yet been observed end to end on a real boarding. The log records each
  step ("Wi-Fi watch: ...", "Periodic check: ...") so a missed wake-up can be diagnosed.
- **Redirect and TLS behaviour** of the portals on Android (certificate chain, https to http hops)
  has only been reasoned about, not observed.
- **Name fields.** Stops prefer `name`, then `label`, then `location.name`. If `name` is a code
  rather than a display name, swap the order in `Parsers.stop`.
- **The app has not been compiled in this environment** (no Android SDK, Google Maven blocked);
  the pure-Kotlin core (models, parsers, demo data) was compiled and exercised on a plain JVM. The
  Android build is done by the GitHub Actions workflow.

## Design notes

- `foregroundServiceType="specialUse"` with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, not `dataSync`:
  Android 15 caps `dataSync` at 6 h per day, a train trip can be longer.
- `START_STICKY`, `startForeground` immediately in `onStartCommand`.
- Notification: custom RemoteViews in `DecoratedCustomViewStyle` (destination, ETA pill, brand
  progress bar; expanded: the remaining stops with delays, then data left and Wi-Fi quality).
  Two channels, silent (low) and lock screen (default importance, no sound), chosen by the switch.
  `IMPORTANCE_LOW` channel, `setOnlyAlertOnce(true)`, `setShowWhen(false)`, `CATEGORY_STATUS`,
  ongoing, a Stop action.
- The `/router/api/pepita` socket.io namespace is not used: polling every 15 s is enough and avoids
  a dependency.
- minSdk 29, target/compile 35, Kotlin 2.0.21, AGP 8.7.3, JVM target 17, Gradle 8.10.2.
