# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Two clients for the same reverse-engineered on-board Wi-Fi API of French trains:

- `wifi_sncf.sh`: bash + curl + jq, macOS/Linux. The reference implementation. When the Android
  app disagrees with it about an endpoint, payload or header, the shell script is right: it is the
  one that has been run on board.
- `android/`: Kotlin POC (`fr.onrails.trainwifi`), no UI library, two dependencies
  (`kotlinx-coroutines` for `StateFlow`, `maplibre` for the map). `android/README.md` is the long
  form of everything below and lists what is still unverified on a real train.

Both talk to `https://wifi.sncf` and `https://wifi.normandie.fr`, same API shape:
`/router/api/connection/status`, `/connection/activate/auto`, `/connection/statistics`,
`/train/gps`, `/train/details`, `/bar/attendance`.

## Commands

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk   # the default JDK 24 is rejected by AGP 8.7.3
cd android && ./gradlew assembleDebug           # -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The SDK lives in `~/Android/Sdk` and `android/local.properties` points at it. Gradle writes to
`~/.gradle` and the first build downloads from `dl.google.com` and `repo.maven.apache.org`, so a
build needs `dangerouslyDisableSandbox: true`.

`.github/workflows/android.yml` builds the same APK on every push to `android-poc` or
`claude/android-poc-**` and uploads it as the `trainwifi-debug-apk` artifact. Debug builds are
signed with `android/app/debug.keystore`, committed so that CI APKs install over each other.

There is no test suite. `AppState.kt`, `Models.kt`, `Parsers.kt`, `Formatting.kt` and `DemoData.kt`
deliberately import nothing from `android.*`, so they compile and run under a plain JVM with
`kotlinc`. That is how the parsers get checked without a device. Keep that property when editing
them.

## Working with the phone over adb

`adb` needs `dangerouslyDisableSandbox: true`: the sandbox has no `/dev/bus/usb`, so inside it
`adb devices` prints an empty list and looks exactly like an unplugged phone.

```bash
adb logcat -d | grep TrainWifi                 # AppState.log() uses println -> tag System.out
adb shell cmd jobscheduler run -f fr.onrails.trainwifi 1   # force the 15-min safety-net probe now
adb shell dumpsys activity services fr.onrails.trainwifi   # "(nothing)" = the service is not up
adb shell run-as fr.onrails.trainwifi cat /data/data/fr.onrails.trainwifi/shared_prefs/trainwifi.xml
adb shell dumpsys deviceidle whitelist | grep trainwifi    # battery-optimisation exemption
adb shell dumpsys wifi | grep mWifiInfo                    # current SSID and IP
```

Forcing the job and reading the log it produces is the fastest way to find out why nothing starts:
the probe writes one line per portal with the exception and the raw body.

## Architecture

### The wake-up chain

Nothing runs between trips. The order matters and each step has a reason that is not obvious from
the code:

1. `WifiSuggestions` registers one open-network suggestion per SSID. This is the only way a normal
   app can make Android join a network unattended. Reading the SSID to detect the train would need
   `ACCESS_FINE_LOCATION`, so the app never does it.
2. `AutoConnect.arm` calls `registerNetworkCallback` with a **PendingIntent**, so the system starts
   `WifiWatchReceiver` even when the process is dead. The request asks for `TRANSPORT_WIFI` and
   deliberately not `NET_CAPABILITY_VALIDATED`: a captive portal is never validated, which is also
   why a JobScheduler network constraint cannot be used here.
3. The receiver probes both portals over that network and starts `TrainWifiService` only if one
   answers. Off a train this is one fast failure.
4. `WatchJobService`, persisted, every 15 min, registers the watch again and repeats the probe.
   ConnectivityService drops a PendingIntent registration about 5 s after it sends the intent, so
   one registration covers a single Wi-Fi network and joining any non-train Wi-Fi uses it up.
   `AutoConnect.rearm` has the trace. Registering again on the spot loops, so the job is where it
   happens.
5. `BootReceiver` re-arms after a reboot or an app update.

`AutoConnect.onWifiAvailable` returns early and **silently** when auto-connect is off or when
`AppState.state.value.phase.isRunning()`. If the log shows nothing at all after forcing the job,
one of those two is the reason.

Android 12+ refuses a foreground-service start from a background receiver unless the app is exempt
from battery optimisation. Without the exemption `AutoConnect.startServiceOrNotify` falls back to a
tappable notification.

### The bound network

Every portal request goes through `Network.openConnection()` in `PortalClient`, never
`URL.openConnection()`. Before activation the Wi-Fi is unvalidated, the default route stays on
mobile data, and `wifi.sncf` only resolves through the train's DNS, so a plain connection leaves
the train. This is the single constraint a naive port of the shell script breaks.

A non-bypassable VPN makes `Network.bindSocket` fail with `EPERM` before any packet leaves the
phone. The app detects that and surfaces `Phase.VPN_BLOCKED`; there is no workaround.

### The portal's JSON contract

The portal reports application errors in the JSON body *and* in the HTTP status. Before activation
`/connection/status` answers **404** with
`{"status_code":404,"status_description":"identifier <mac> does not have any (in)active grants"}`.
That body is the portal identifying itself. `PortalClient.toJson` therefore accepts any JSON body
carrying `status_code` whatever the HTTP code, and fails only on a body that is not JSON. Do not
"fix" this back to a plain `code >= 400` check: that silently turns every boarding into
"not a train".

`Parsers` are tolerant on purpose: the two portals disagree on field names and firmware versions
add and remove fields. Every accessor is optional, and `AppState.logRaw` dumps the truncated raw
body of every response so a parser can be fixed from inside a train.

### The map

`TrainMap` uses MapLibre on the portal's own map: PMTiles archives at `/maps/europe.pmtiles` and
`/maps/osm_railways.pmtiles`, style at `/karto/style-{dark,light}.json`, sprites and Avenir glyphs
under `/maps/`. MapLibre reads `pmtiles://` natively from 11.8.0. Three things are needed and none
is obvious:

- The style calls its own host `http://localhost:8000`. `TrainMap.rewrite` swaps in the portal base
  URL, which fixes the tiles, the sprite and the glyphs in one replace.
- MapLibre opens its own sockets, so `HttpRequestUtil.setOkHttpClient` hands it a client with the
  Wi-Fi `Network`'s socket factory **and** its DNS. The socket factory alone leaves name resolution
  on the default network, where `wifi.sncf` does not resolve.
- `MapLibre.setConnected(true)`, because MapLibre reads the default network to decide whether it is
  online and that is mobile data until the portal is activated.

`BoundNetwork` carries the `Network` and the `Portal` from the service to the activity.
`abiFilters` is arm64-only: MapLibre is about 11 MB of native code per ABI.

### State

`AppState` is a process-wide singleton holding a `MutableStateFlow<TrainState>` and a rolling
200-line log. `TrainWifiService` is the only writer; `MainActivity` and `TripNotification` read it.
`Phase` drives both the notification and the screen.

`TrainWifiService` uses `foregroundServiceType="specialUse"`, not `dataSync`: Android 15 caps
`dataSync` at 6 h per day and a trip can be longer. It self-stops to standby 2 min after the Wi-Fi
is gone (tunnel grace) or after 10 min on a Wi-Fi with no portal, and re-arms the watch on the way
out.

## Conventions

- British English in prose and comments.
- Platform APIs over libraries: HTTP is `HttpURLConnection`, JSON is `org.json`, the UI is
  hand-written views and `RemoteViews`. Adding a dependency needs a reason.
- The map must not need a location permission: it draws the train's GPS as reported by the portal.
