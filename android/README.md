# Train Wi-Fi (Android POC)

Android counterpart of [`wifi_sncf.sh`](../wifi_sncf.sh). It joins the on-board Wi-Fi of French
trains on its own, clears the captive portal without the user touching anything, and shows the
trip (destination, ETA, delay, progress, speed) in a permanent notification.

It is a proof of concept: plain Kotlin, platform APIs, one third-party dependency
(`kotlinx-coroutines`, for `StateFlow`). No location permission.

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
| `TrainWifiService.kt` | Foreground service (`specialUse`), network callback, poll loop, demo loop |
| `PortalClient.kt` | `HttpURLConnection` bound to the Wi-Fi `Network`, manual redirects (3 hops) |
| `PortalApi.kt` | The five endpoints plus activation |
| `Parsers.kt` | Tolerant `org.json` parsing of both stop shapes, ISO date handling |
| `Models.kt` | Data classes, `Trip` progress / next stop / destination logic |
| `TripNotification.kt` | Ongoing silent notification with progress bar and `BigTextStyle` |
| `MainActivity.kt` | Status card, buttons, SSID editor, log view |
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
2. Android 13+: allow **notifications** (the trip info lives there; the service runs even if you
   refuse, but you will not see anything).
3. Android 11+: a system dialog asks to let the app **suggest networks**. Accept. On Android 10 the
   question arrives later as a notification the first time a suggested network is in range.
   If the app was previously refused, `addNetworkSuggestions` returns `APP_DISALLOWED` and the app
   shows where to fix it: Settings > Network & internet > Internet > Network preferences >
   *Apps that can suggest networks* (wording varies by vendor).
4. Optional but recommended: exclude the app from battery optimisation so the poll loop is not
   throttled on long trips.

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

**Stop** (button or notification action) ends the service and removes the notification. The Wi-Fi
suggestions stay registered until you edit the list; **Save & apply SSIDs** removes all previous
suggestions and registers the new list.

## Demo trip

**Demo trip** starts the service with canned responses for both portals (`wifi.sncf` flat stops
with `delay` fields, `wifi.normandie.fr` nested `location` / `arrival` with offset-less dates and a
`null` progress). It alternates between the two every 10 s, needs no network at all, and drives the
exact same parsers and notification code as a real trip.

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
- Notification: `IMPORTANCE_LOW` channel, `setOnlyAlertOnce(true)`, `setShowWhen(false)`,
  `CATEGORY_STATUS`, ongoing, a Stop action.
- The `/router/api/pepita` socket.io namespace is not used: polling every 15 s is enough and avoids
  a dependency.
- minSdk 29, target/compile 35, Kotlin 2.0.21, AGP 8.7.3, JVM target 17, Gradle 8.10.2.
