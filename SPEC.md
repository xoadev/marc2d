# marc2d

**M**óvil **A**ndroid **R**eciclado **C**omo **C**onsola **D**omótica: MAR + C² + D.
(Spanish for "recycled Android phone as a home automation console".)

Android app for a wall panel: a fixed phone, powered by PoE, that acts as the console of the
house. It is not the control hub: the logic lives in Home Assistant (HA) and the intercoms, and the panel
connects to them and exposes their functions:

- To Home Assistant, through its WebSocket API: it shows its data and handles its events.
- To the intercoms, over SIP: it handles their calls.
- Optionally, it offers its camera to Frigate or a similar system.

The architecture diagram is in the [README](README.md#architecture).

The project is in testing and has no users: break whatever needs breaking.

## Main screen

- The time in large type and the date.
- In the top right corner, the current weather and the forecast for the next 8 hours, in white
  and gray, in a list next to the time. They come from the `weather.*` entity
  named in the configuration, not from a service of our own. They update every 10 minutes and
  are hidden if the data is more than an hour old.
- At the bottom, a warning only if something fails: HA, registration with an intercom or the microphone permission.
- Tapping anywhere opens the menu, with **Cameras** and **Settings**.

### Cameras

They come from `cameras` in the configuration, with their HA `camera.*` entity, which provides the video as MJPEG
using the panel's token, with no new credentials. You swipe from one to the next, and only the visible one is
downloaded. If it has `intercom`, it shows **Talk**, which calls that intercom. Another panel will appear here
once it publishes its camera and is in HA: talking to it is still to be decided.

### Droid

A 16×16 black and white sprite walks in jumps along the bottom strip: it avoids burning the OLED, and the
eye color only appears when something matters. When its state changes it stays where it is. Asleep, every
20 minutes it walks to another spot and goes back to sleep.

| State     | Eye                     | What triggers it                             |
| --------- | ----------------------- | -------------------------------------------- |
| Intruder  | red band                | HA, with `select.*_state`                    |
| Alarm     | yellow                  | HA, with `select.*_state`                    |
| Recording | red lens                | HA, with `select.*_state`                    |
| Offline   | an X                    | the panel, if there is any warning on screen |
| Sleeping  | closed                  | the panel, below 5 lx                        |
| Walking   | looks where it is going | the panel, if nothing else applies           |

The table order is the priority. What HA commands is retained in MQTT: on restart, the panel
recovers whatever alarm was active.

## Intercom

- The panel registers as a SIP extension on the intercom, which acts as the SIP server.
- HA detects the button press with the Dahua integration and publishes `RING` to
  `marc2d/doorbell/<intercom>`, not retained. All panels ring, even if the app is in the
  background, and stop ringing after 45 seconds.
- On answering, the panel publishes `ANSWERED`, the others stop ringing, and it calls the intercom's
  number over SIP (`number`, `8001` by default). The VTO picks up by itself and provides two-way audio and
  video. If HA or Mosquitto goes down, the panels do not ring, but DMSS keeps working.
- An incoming SIP call to the extension is also handled.
- From its camera, **Talk** calls the intercom without the doorbell having been pressed.
- Home network only. From outside, over Tailscale, the subnet router does SNAT and the
  intercom cannot reach the panel's `Contact`.
- Outside this version: opening the door.

### Dahua VTO

Verified against a VTO2202F (UDP 5060), which is Asterisk inside:

- Extension `9901#1` registers (`200 OK`) with the URI `sip:9901%231@<vto>`, with the
  `#` escaped, and the Digest user `9901#1`, literal.
- The challenge is `realm="asterisk"`, MD5 and without `qop`.
- The button calls group `9901`, and no extension receives that `INVITE`: the server stays at
  `100 Trying`. Direct calls to an extension do arrive. The button number is not changed
  so as not to risk DMSS, which receives the doorbell by another route.
- The VTO accepts calls to `8001`: two-way PCMU audio and H.264 video to the panel. Right
  after a button press it does not answer, while its own group call lasts: the panel retries every 2
  seconds, for up to 45.
- A device that reaches the VTO through the Tailscale subnet route (`--accept-routes`) leaves with SNAT
  and the result is not valid: test from the direct LAN.

## MQTT

The panel connects to the broker named in the configuration, if MQTT is on, and registers itself in HA, with
device discovery (`homeassistant/device/marc2d_<panel>/config`). If it goes down, the
MQTT last will marks it as `unavailable`. The intercom does not depend on MQTT.

| Entity                         | Data                  | When it is published                   |
| ------------------------------ | --------------------- | -------------------------------------- |
| `sensor.*_light`               | ambient light, in lux | if it changes by at least 2 lx and 20% |
| `sensor.*_battery`             | level, in %           | if it changes, checked every minute    |
| `sensor.*_battery_temperature` | battery, in °C        | if it changes, checked every minute    |
| `binary_sensor.*_charging`     | charging or not       | if it changes, checked every minute    |
| `siren.*_siren`                | turns on and off      | on change                              |
| `select.*_state`               | droid state           | on change                              |

The siren sounds at full volume on the alarm channel and stops by itself after 10 minutes, because if the
connection is lost HA could no longer turn it off.

## Speaker

Still missing: HA will ask for a message to be read aloud with the system TTS, also over MQTT.

Voice commands: the panel is an Assist satellite. It is activated with an on-screen button, sends the
microphone audio to the HA pipeline (`assist_pipeline/run`) and plays the response. The
wake word is outside this version.

## Presence

The panel turns the screen on when the presence detector in its room sees someone, and
turns it off when it stops seeing them. The detector is an HA `binary_sensor.*` entity chosen in the
configuration; the panel does not use its own sensors.

## Camera

The panel can publish its camera as an RTSP stream on the home network, to be consumed by Frigate or
a similar system. It is disabled by default and enabled in the configuration. The panel only publishes the
video: detection and recording are up to whoever consumes it. Stream authentication is still to be
decided.

## Device

- A Pixel 3a and, later, a Pixel 4a, powered by PoE and running LineageOS. `minSdk` is 35: the 3a
  runs LineageOS 22.2, with Android 15.
- LineageOS runs without Google services: the app cannot depend on them. It has no TTS engine
  either: `marc2d_say` needs one, still to be chosen.
- LineageOS blocks the network for new apps if restricted networking is enabled: the panel needs
  **Allow network access** in its Android settings.
- When plugged in, Android does not enter Doze: the foreground service is enough not to miss calls.
- The app is the home screen and starts by itself when the phone is turned on, with no screen lock. It is
  the app that launches the service, of type `microphone`: Android 15 does not allow that type to start from
  `BOOT_COMPLETED`. The service reconnects to HA and the intercoms by itself.
- Kiosk mode: the app is the Device Owner and uses Lock Task, so you cannot leave for other apps.
  From the configuration you can open the Android settings, and the kiosk comes back on
  return, or leave it completely.
- The panel is always in landscape, even if the phone is rotated.
- Both screens are OLED: with the screen on, the time shifts a few pixels now and
  then to avoid burning it.
- LineageOS is installed, among other reasons, to limit battery charging to 80%: always at
  100% it could swell. At the limit, Android reports that it is not charging even though it has power: it is
  normal for `binary_sensor.*_charging` to be off. Even so, check on it from time to time.

## Configuration

In the app you only enroll the panel, which gives it its URL, token and name. Each panel has its own name, such as
`kitchen`, from which its MQTT topics and its entities come. Everything else is set in HA, in the panel's options in
the integration, and the panel receives it right away through `marc2d/config/subscribe`:

```json
{
  "intercoms": [
    { "name": "Parking", "server": "192.0.2.10", "extension": "9901#1", "password": "…", "number": "8001" }
  ],
  "weather": "weather.home",
  "mqtt": { "server": "192.0.2.20", "port": 1883, "username": "…", "password": "…", "sensors": true, "siren": true },
  "cameras": [
    { "entity": "camera.door", "name": "Parking", "intercom": "Parking" }
  ]
}
```

| Toggle          | When it is off                                                |
| --------------- | ------------------------------------------------------------- |
| Intercom (SIP)  | no intercoms are sent: the panel unregisters                  |
| Cameras         | no cameras are sent: the menu hides them                      |
| Weather         | no weather entity is sent: the panel hides it                 |
| MQTT            | no connection: no doorbell, sensors, siren or states from HA  |
| Publish sensors | HA deletes those entities and the panel stops publishing them |
| Siren           | HA deletes the entity and the panel ignores the command       |

- What is off is left out of the response, so the panel needs no logic for most toggles.
- The command identifies the panel by its user: no other user, not even an administrator, gets a panel's secrets this
  way. Administrators can see them in the form, masked.
- It is kept in the app's private storage, along with the connection, and never in the repo: the
  panel keeps handling calls even if HA is down.
- The presence detector and camera publishing will be added to this response in their
  deliveries.

## Delivery order

1. Time, date, weather, kiosk mode and answering the intercom.
2. Messages and siren.
3. Turning the screen on with presence.
4. Voice commands.
5. Publishing the camera.

## Home Assistant integration

Our own integration, installable with HACS, in `custom_components/marc2d/`. It replaces the configuration
script and will replace MQTT, so that installing marc2d does not require Mosquitto or writing YAML. It requires HA 2026.10.

- **Enrollment (done):** the panel is added in HA, which shows a QR code with the internal URL and a one-time code
  that expires in 10 minutes. The panel scans it with the front camera, or types both, and exchanges the code at
  `POST /api/marc2d/enroll` for a token. The QR code never carries the token. Each panel gets its own
  unprivileged, local-only user: removing the panel in HA removes that user and revokes its access. The endpoint has
  no authentication, so 10 wrong codes kill every open code.
- **Configuration (done):** per-panel form in HA; see [Configuration](#configuration).
- **Entities (pending):** its own, over the WebSocket the panel already uses. If it closes, the panel becomes
  `unavailable`.
MQTT stays until the integration has its own entities and works in a real home. The doorbell button entity will
move to the form with them.

## Decisions

- Kotlin and Jetpack Compose with Material 3.
- Built with Kotlin Toolchain, without Gradle.
- Linter: ktlint with the Compose rules, through our own plugin in `plugins/ktlint`, because
  the Toolchain does not include one. detekt stays out while 2.0 is still in alpha. The Toolchain does not
  run Android Lint.
- SIP with Linphone SDK. It is GPLv3: fine for personal use; distributing it to third parties requires its
  commercial license.
- HA with Ktor Client, OkHttp engine and kotlinx.serialization: WebSocket and JSON over coroutines.
- MQTT with our own 3.1.1 client over `Socket`: a single connection creates the panel's entities,
  publishes its readings, receives commands and tells HA if the panel goes down. Paho has not published releases
  since 2020, and HiveMQ pulls in Netty, whose duplicate `META-INF` files cannot be excluded with the
  Toolchain. The panel only needs QoS 0 when publishing and no TLS, because it runs on the LAN. It sits behind
  the `MqttClient` interface, so that it can be replaced by a library by touching a single class.
- Configuration with Jetpack DataStore.
- QR codes with CameraX and ZXing core: ML Kit depends on Google services, and CameraX spares us the camera
  lifecycle.
- RTSP with RootEncoder and RTSP-Server. They are maintained, but by a single author and published on
  JitPack: the camera is optional and sits behind an interface so they can be replaced.
- TTS, audio and screen with the Android APIs, without libraries.
- No dependency injection framework: the service creates and connects the pieces by hand.
- A foreground service keeps the SIP registration and the connection to HA, because the intercom
  does not send push.
- Only stable versions of libraries with recent releases are used. The versions are in
  `libs.versions.toml` and libraries enter the catalog when first used.
