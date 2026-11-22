# marc2d

**M**óvil **A**ndroid **R**eciclado **C**omo **C**onsola **D**omótica: MAR + C² + D.
(Spanish for "recycled Android phone as a home automation console".)

Android app for a wall panel: a fixed phone, powered by PoE, that acts as the console of the
house. It is not the control hub: the logic lives in Home Assistant (HA) and the intercoms, and the panel
connects to them and exposes their functions:

- To Home Assistant, through its WebSocket API: it shows its data and handles its events.
- To the intercoms, over SIP: it handles their calls.
- To the phone line, if it has a SIM: it shows its calls.
- Optionally, it offers its camera to Frigate or a similar system.

The architecture diagram is in the [README](README.md#architecture).

The project is in testing and has no users: break whatever needs breaking.

## Main screen

- The time in large type and the date.
- In the top right corner, the [widgets](#widgets), two per row, in the order of the configuration. The droid never
  covers them.
- At the bottom, a warning only if something fails: HA, registration with an intercom or the microphone permission.
- If a permission is missing, a warning icon in the top left corner asks for it again, or opens the app's Android
  settings if Android no longer shows the dialog.
- Tapping a widget that has a detail opens it; tapping anywhere else opens the menu, with **Cameras** and **Settings**.
- If nobody touches it for a while, the [photos](#photos) take its place.

### Widgets

Small squares on the main screen, such as the weather, the electricity price or the car's battery. What they show is
decided in Home Assistant: the panel knows nothing of weather, prices or cars, only how to draw a few pieces.

- **Each widget is a Jinja template**, with a name, in the panel's options. The integration renders it and sends the
  panel the result, a JSON object made of the pieces below. It renders it again whenever an entity it reads changes, and
  every minute if it uses `now()`, so a widget can read as many entities as it needs.
- **A template that fails, or whose result is not a valid widget, is left out**, and HA's log says which widget and
  why. The rest keep showing.
- **The widgets reach the panel in their own event**, `widgets`, with the whole list each time one changes. The panel
  keeps the last list, and hides a widget once its `expires` has passed: better nothing than old data.

| Piece     | What it is                                                                                              |
| --------- | ------------------------------------------------------------------------------------------------------- |
| `icon`    | a 12×12 pixel icon: `battery`, `car`, `thermometer`, `power` or `water`. The battery fills with `level` |
| `badge`   | a small icon next to it: `charging`                                                                     |
| `tone`    | `gray`, the default, `white` or `yellow`: the colour of the icon, the lines and the level               |
| `lines`   | texts, each `{text, size, tone}`: `size` is `large`, the default, or `small`                            |
| `columns` | for a forecast: each `{label, symbol, value, sub}`, `symbol` being a condition of HA's weather          |
| `strip`   | a row of cells, `{cells, current}`: each cell `true` in white or `false` in gray, `current` framed      |
| `level`   | from 0 to 1: a bar of ten cells. With the `charging` badge, the next empty cell is framed               |
| `tap`     | what opens when it is tapped: `bars` or `columns`, below. Without it, the widget is not a button        |
| `expires` | an ISO date and time with its offset: from then on the panel hides it                                   |

The integration adds `name`, the widget's name in the configuration, which accessibility services read with its lines.

The two screens a widget opens, full screen, closing with the X, by tapping and on their own after 2 minutes:

- **`bars`**: `{title, subtitle, bars, current}`, each bar `{label, value, lit}`. The bars in white when `lit` and in
  gray otherwise, the `current` one framed, and their labels below.
- **`columns`**: `{title, headline, value, columns}`, the columns as in the widget, over the whole width.

#### Ready-made widgets

The integration adds three macros to every widget template. They are the usual widgets, and an example to write others;
a template can also build its JSON by hand and call `to_json`. Their own words, such as "Cheap now" or the condition of
the weather, are in HA's language: English, Spanish and Galician, and English for any other. What a template writes is up to
whoever writes it.

- **`price_widget(entity, cheap_hours=6, title=none)`**: the price by the hour of an entity with
  `raw_today` and `raw_tomorrow`, lists of `start` and `value`: the format of Nord Pool, ENTSO-e and EV Smart Charging.
  Values every 15 minutes count as such. The cheap ones are the lowest `cheap_hours` of the next 24 hours. It says
  whether now is cheap, the first two cheap windows of the next 18 hours, and a strip of those 18 hours. Tapped, it
  opens the next 24 hours as bars, with every hour under them and the lowest and highest values above, under `title`
  or the word for electricity.
- **`weather_widget(weather, forecast)`**: three columns of 6 hours, each with the most significant condition (rain
  beats cloud, cloud beats sun) and the highest temperature over the lowest. Tapped, it opens the current weather and
  the next 24 hours. A template cannot call a service, and the hourly forecast only comes from `weather.get_forecasts`,
  so `forecast` is a trigger-based template sensor that keeps it in its `forecast` attribute:

  ```yaml
  template:
    - trigger:
        - trigger: time_pattern
          minutes: /30
      action:
        - action: weather.get_forecasts
          target: {entity_id: weather.home}
          data: {type: hourly}
          response_variable: forecast
      sensor:
        - name: Hourly forecast
          state: "{{ now().isoformat() }}"
          attributes:
            forecast: "{{ forecast['weather.home'].forecast }}"
  ```

  The widget expires an hour after that sensor last changed.
- **`reading_widget(entity, icon=none, warn_below=none, warn_above=none, charging=none)`**: the value and unit of any
  entity, such as the car's battery. A percentage gets a level. Without an icon its name says what it is. It turns
  yellow past a warning limit, unless `charging`, a binary sensor, is on: then it shows the `charging` badge.

```jinja
{{ price_widget('sensor.electricity_price') }}
{{ weather_widget('weather.home', 'sensor.hourly_forecast') }}
{{ reading_widget('sensor.car_battery', icon='car', warn_below=20, charging='binary_sensor.car_charging') }}
```

### Photos

A screensaver with photos from a Home Assistant media source: a folder of My media, an Immich album or any other source
the media browser shows. The panel needs no other credentials, and nothing is copied to it.

- **When:** after `idle_minutes` without any touch, the photos take the place of the main screen, and any touch brings
  the clock back. They only show while the droid would just be walking: an alarm, the camera, a voice command, a
  warning or a dark room keep the clock, which shows them. Calls, cameras, the menu and settings come first, as always.
- **Screen:** Android still turns it off after its own screen timeout, which says how long the photos are seen. With
  `keep_screen_on` the photos keep it on until someone touches it or the room goes dark. With the screen off nothing is
  downloaded.
- **Photos:** one every `seconds`, with a crossfade, shuffled again on each pass or by name, whole with black bars or
  cropped to fill the screen. With `clock`, the time and date go small in a corner,
  shifting a few pixels now and then like the main clock. The pass goes on where it was the
  next time the screensaver starts.
- **Orientation:** with `match_orientation`, on by default, only photos shaped like the screen show: landscape ones with
  the panel in landscape, portrait ones in portrait, and nearly square ones either way. HA cannot tell a photo's shape
  without opening it, so the panel learns it the first time it downloads each one, after turning it upright, and from
  then on skips the others without downloading them. If no photo fits, they all show.
- **Where they come from:** the folder, and whether to walk its subfolders, stay in HA. The panel asks for the photos
  with `marc2d/photos`, on connecting and on each refresh, every 10 minutes. HA lists the images, resolves each one and
  signs its address for the panel's own user, for a day, so the panel downloads them without its token and never sends
  it outside the WebSocket. HA's own addresses come as paths, joined to the address the panel knows for HA. A failed
  refresh keeps the photos it had.
- **Limits:** up to 2000 photos in 200 folders, walked again on each refresh; photos above 40 MB are skipped, and the
  rest are decoded no larger than the screen. Android turns them upright from their EXIF data.
- **Folder:** a plain path, such as `photos`, is a folder of My media (`/media/photos`). Any other source is its
  `media-source://` identifier, or the media browser's address, copied with that folder open. The form checks that HA
  can open it and that it has photos.
- **Log:** the panel logs how many photos it has, and why each one that fails could not be shown; HA logs the
  subfolders it skipped and the limits it reached.

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
| Sound     | large red lens          | the panel, while it streams the microphone   |
| Recording | red lens                | HA, or the panel while its camera is on      |
| Listening | large, with a glint     | the panel, after the wake phrase             |
| Thinking  | a glint going round     | the panel, until the answer starts sounding  |
| Offline   | an X                    | the panel, if there is any warning on screen |
| Sleeping  | closed                  | the panel, below 5 lx                        |
| Walking   | looks where it is going | the panel, if nothing else applies           |

The table order is the priority. HA restores what it commanded: on restart, the panel recovers
whatever alarm was active.

## Intercom

- The panel registers as a SIP extension on the intercom, which acts as the SIP server.
- Each intercom has its doorbell button entity in the form. When it fires, the integration tells each panel that has that
  button in its form to ring, once, even if the app is in the background, and they stop ringing after 45 seconds.
- While it rings, the panel shows the camera linked to that intercom, from HA: the call's video only starts on answering.
- On answering, the panel tells the integration, the others stop ringing, and it calls the intercom's
  number over SIP (`number`, `8001` by default). The VTO picks up by itself and provides two-way audio and
  video. If HA goes down, the panels do not ring, but DMSS keeps working.
- An incoming SIP call to the extension is also handled.
- **Audio over SIP:** what Linphone negotiates and plays. An intercom that does not follow the negotiation, as the VTO,
  goes over RTSP instead.
- **Over RTSP:** each intercom can take an RTSP URL, with its user and password. Then the panel opens it as soon as it
  rings, to show who is at the door without waiting, and on answering the street's sound and the panel's voice, through
  the ONVIF backchannel, go over the same connection. SIP only tells the intercom someone answered or hung up, with a
  call that only receives audio and video, unseen and muted: offered less, the VTO answers no stream in common and
  Linphone drops the call. Talk does without SIP. While the VTO is still calling, as DMSS, it answers 486 Busy: the
  panel retries every 2 s, and each intercom can have a button that HA presses when a panel answers, such as the VTO's
  Cancel Call, which stops that call so the next try gets through. A Dahua VTO plays the voice of a SIP call with a
  regular click, whatever its format, pace or content, even digital silence; through the backchannel, as DMSS does, it
  is clean. The panel's own RTSP client asks for the tracks with `Require: www.onvif.org/ver20/backchannel`,
  authenticates with Digest or Basic, takes the interleaved channels the server chooses (the VTO answers 10-11 whatever
  is asked), decodes H.264 with the hardware decoder and sound in L16, PCMA or PCMU, and sends the voice in 20 ms
  packets. The session knows nothing of the screen, microphone or speaker, so that something else, such as a bot
  attending the door, can use it. For the VTO2202F: `rtsp://admin:…@ADDRESS/cam/realmonitor?channel=1&subtype=1`,
  without `proto=Onvif`, which it refuses with 401.
- From its camera, **Talk** calls the intercom without the doorbell having been pressed.
- Home network only. From outside, over Tailscale, the subnet router does SNAT and the
  intercom cannot reach the panel's `Contact`.
- Outside this version: opening the door.

### Dahua VTO

Verified against a VTO2202F-P-S3 (UDP 5060), which is Asterisk inside:

- Extension `9901#1` registers (`200 OK`) with the URI `sip:9901%231@<vto>`, with the
  `#` escaped, and the Digest user `9901#1`, literal.
- The challenge is `realm="asterisk"`, MD5 and without `qop`.
- The button calls group `9901`, and no extension receives that `INVITE`: the server stays at
  `100 Trying`. Direct calls to an extension do arrive. The button number is not changed
  so as not to risk DMSS, which receives the doorbell by another route.
- Its SIP audio is `PCM/16000`, payload type 97, little-endian, whatever the call agrees, and it plays what it receives
  as that too: Linphone drops it, and its PCMA reached the street as noise. Played and sent by the panel itself, the
  voice still clicked, so the VTO goes over RTSP, where it is clean.
- The VTO accepts calls to `8001`, with that audio and H.264 video to the panel. Right
  after a button press it does not answer, while its own group call lasts: the panel retries every 2
  seconds, for up to 45.
- A device that reaches the VTO through the Tailscale subnet route (`--accept-routes`) leaves with SNAT
  and the result is not valid: test from the direct LAN.

## Phone line

A panel with a SIM shows its calls on the intercom's call screen, named by the number, or by the name the network gives,
and without video: **Answer** takes it, **Hang up** turns it down or ends it, and it always talks on the speaker.

- The panel is the default dialer, which the Device Owner sets when it enters the kiosk: Telecom then hands it the calls
  instead of showing its own screen, which the kiosk would hide. It is the dialer only to show calls: it has no keypad,
  and dialing just opens the panel.
- The ringtone is Telecom's, the phone's own: the panel does not ring them itself.
- One call at a time: a second one while there is a call is turned down.
- The intercom comes first: if it rings during a phone call, its screen covers the phone's, and the phone call goes on
  behind it until the intercom's ends.
- The log only says that a call rang, was answered or ended: never the number.

## Entities

The panel connects to HA with its enrollment token and needs nothing else: no broker and no extra user. The integration
creates its device and entities. They are `unavailable` while the panel's connection is closed. The intercom does not
depend on this connection once registered.

| Entity                         | Data                  | When it is reported                    |
| ------------------------------ | --------------------- | -------------------------------------- |
| `sensor.*_light`               | ambient light, in lux | if it changes by at least 2 lx and 20% |
| `binary_sensor.*_presence`     | someone seems there   | on change, and 60 s after the last one |
| `sensor.*_battery`             | level, in %           | if it changes, checked every minute    |
| `binary_sensor.*_overheating`  | battery at 45 °C +    | HA works it out from the temperature   |
| `sensor.*_wi_fi_signal`        | Wi-Fi signal, in dBm  | if it moves 5 dB, checked every minute |
| `update.*_app`                 | the app's version     | on each connection                     |
| `sensor.*_battery_temperature` | battery, in °C        | if it changes, checked every minute    |
| `binary_sensor.*_charging`     | charging or not       | if it changes, checked every minute    |
| `siren.*_siren`                | turns on and off      | on change                              |
| `select.*_state`               | droid state           | on change, decided by HA               |
| `media_player.*_speaker`       | playing or idle       | on change                              |
| `number.*_screen_brightness`   | brightness, in %      | decided by HA, sent on each connection |
| `camera.*_camera`              | the panel's camera    | if published, while someone watches    |
| `binary_sensor.*_camera_on`    | the camera is on      | if published, on change                |

The panel reports with `marc2d/state` and receives commands and configuration as events of `marc2d/subscribe`. Both
identify it by its user. Only the entities whose value changed are written.

The siren sounds at full volume on the alarm channel and stops by itself after 10 minutes, because if the
connection is lost HA could no longer turn it off.

## Speaker

The panel is a `media_player.*_speaker`: `tts.speak` targets it like any other speaker. HA generates the speech and the
panel plays the URL it receives, so it needs no TTS engine, which LineageOS lacks. Its volume is set from HA, with
`media_player.volume_set`. HA's own media arrives as a path, which the panel joins to the address it knows for HA; any
other URL is played as it is.

## Voice commands

Off by default, because it keeps the microphone open. The panel detects its wake phrase, "jarvis" unless the form says
otherwise, on the device with Vosk, and audio
leaves it only after that phrase, to `assist_pipeline/run`. HA transcribes it, runs the assistant chosen in the form and
returns speech, which the panel plays. The eye shows when it is listening, for up to 10 seconds. A call takes the
microphone, and listening resumes when it ends. The first time, it downloads a 40 MB model, checked by its hash.

Vosk only confirms the phrase after about a second of silence, and the request may have started by then: the panel keeps
the last 2 seconds it heard and sends Home Assistant what came after the phrase, as Vosk tells when it ended. The request
goes four times louder: from across the room the voice arrived at about -30 dBFS, and Whisper made up words from it.

The phrase has to be said alone, with a pause before and after: inside a sentence it is someone talking. Vosk hears only
what its grammar lists, so with few entries it fits nearly any speech into the phrase: the first grammar woke on 15 of 38
other sentences. The grammar now has general Spanish as decoys, not chosen for one phrase, and needs a confidence of 0.9.
On 100 other sentences said by synthetic voices, "jarvis" wakes on one, "Harvey", and "oye mark" on none, and both on every
try of the phrase, "jarvis" also when said "yarvis". Real voices have yet to be measured: the log gives each one's
confidence. The model only knows its vocabulary, and Android's Vosk cannot ask about a word, so a phrase with any other
word never wakes. "yarvis" is one of those: write "jarvis".

## Presence

The panel guesses that someone is there from sudden changes of light, with the same threshold as `sensor.*_light`, and
reports it as `binary_sensor.*_presence` for a minute after the last change. It goes with the other sensors. Slow changes,
such as the sun, do not count, nor does the panel changing its own brightness. It is a guess: it sees nothing in the dark
or from someone who stays still, and a lamp or a passing cloud can trigger it. What to do with it is decided in HA.

Still to do: the panel turns the screen on when the presence detector in its room sees someone, and
turns it off when it stops seeing them. The detector is an HA `binary_sensor.*` entity chosen in the
configuration; the panel does not use its own sensors.

## Camera

The panel can publish its camera as an RTSP stream on the home network, and HA gets a `camera.*_camera` entity from it.
It is off by default and turned on in the panel's form. The panel only publishes the video: detection and recording
are up to whoever consumes it, in HA or in Frigate, which use the same URL:
`rtsp://marc2d:<password>@<panel>:8554/camera`.

- **On demand:** the panel always listens, and it is the first authenticated client that turns the camera on. It turns
  off 10 seconds after the last one leaves, so that a client that connects twice in a row does not make it blink.
  Anything that watches all the time, such as Frigate, keeps it on, and then the phone's heat has to be watched.
- **Seen:** whoever is in front has to know. While the camera is on, including those 10 seconds, the screen stays on and
  the droid shows the Recording state, above anything except an alarm, and the screens without the droid show a red square.
  HA can still set the brightness to its lowest, 1%.
- **Password:** RTSP Digest, with the user `marc2d`. The form generates a random password if none is given, and one
  typed there needs 12 characters. It reaches the panel with its configuration. Each wrong password waits a second,
  three end the connection, and one without a correct password within 5 seconds is closed: there are only 8 places.
- **Address:** the panel reports its IPv4 address and HA follows it. Frigate does not: give the panel a fixed address.
- **Pictures:** HA takes them from the stream, so a card turns the camera on while it is on screen.
- **Sound:** off by default, with its own switch. AAC from the microphone at 16 kHz, in the same stream, and the droid
  has its own eye for it. If the microphone fails the video goes on without it. A call may leave the stream silent.
- **Video:** H.264 from the front camera, 15 fps at 2 Mbps, 720p, turned to match the panel when the
  stream starts. Only RTP over the RTSP connection: other clients than HA and Frigate may need to ask for TCP.
- The camera can only have one owner: scanning a QR code takes it, and clients are disconnected and retry.

## Device

- A Pixel 3a and, later, a Pixel 4a, powered by PoE and running LineageOS. `minSdk` is 35: the 3a
  runs LineageOS 22.2, with Android 15.
- LineageOS runs without Google services: the app cannot depend on them. It has no TTS engine
  either: speech comes from HA.
- LineageOS blocks the network for new apps if restricted networking is enabled: the panel needs
  **Allow network access** in its Android settings.
- When plugged in, Android does not enter Doze: the foreground service is enough not to miss calls.
- The app is the home screen and starts by itself when the phone is turned on, with no screen lock. It is
  the app that launches the service, of type `microphone` and `camera`: Android 15 does not allow those types to start
  from `BOOT_COMPLETED`. The service reconnects to HA and the intercoms by itself.
- Kiosk mode: the app is the Device Owner and uses Lock Task, so you cannot leave for other apps.
  From the configuration you can open the Android settings, and the kiosk comes back on
  return, or leave it completely.
- The panel follows the phone's orientation and its rotation lock, in portrait or landscape.
- Both screens are OLED: with the screen on, the time shifts a few pixels now and
  then to avoid burning it.
- LineageOS is installed, among other reasons, to limit battery charging to 80%: always at
  100% it could swell. At the limit, Android reports that it is not charging even though it has power: it is
  normal for `binary_sensor.*_charging` to be off. Even so, check on it from time to time.

## Configuration

In the app you only enroll the panel, which gives it its URL, token and name. Each panel has its own name, such as
`kitchen`, from which its entities come. Everything else is set in HA, in the panel's options in
the integration, and the panel receives it right away through `marc2d/subscribe`:

```json
{
  "intercoms": [
    { "name": "Parking", "server": "192.0.2.10", "extension": "9901#1", "password": "…", "number": "8001" }
  ],
  "cameras": [
    { "entity": "camera.door", "name": "Parking", "intercom": "Parking" }
  ],
  "voice": { "pipeline": "01h...", "phrase": "jarvis" },
  "rtsp": { "password": "…", "audio": true },
  "photos": {
    "idle_minutes": 1, "seconds": 30, "order": "shuffle", "fit": "fit", "match_orientation": true, "clock": true,
    "keep_screen_on": false
  }
}
```

The form is a menu that links to a page per feature, and submitting a page saves it right away. HA forms cannot show fields
depending on another field, so each page has its switch and its data. Lists can stay empty, and what a turned-off
feature had is kept. The [widgets](#widgets) are not in this response: they arrive in their own event.

| Feature  | When it is off                                               |
| -------- | ------------------------------------------------------------ |
| Intercom | no intercoms are sent: the panel unregisters, does not ring  |
| Cameras  | no cameras are sent: the menu hides them                     |
| Sensors  | HA deletes those entities and ignores those reports          |
| Siren    | HA silences the panel and deletes the entity                 |
| Speaker  | HA stops the audio and deletes the entity                    |
| Voice    | no voice is sent: the panel does not open the microphone     |
| Publish  | no password is sent: the panel stops, HA deletes the entity  |
| Widgets  | HA stops rendering them and sends an empty list              |
| Photos   | no photos are sent: the panel keeps the clock                |

- What is off is left out of the response, or only changes HA, so the panel needs no logic for the toggles.
- A camera gets its `intercom` only if that intercom is sent: otherwise **Talk** would do nothing.
- The command identifies the panel by its user: no other user, not even an administrator, gets a panel's secrets this
  way. Administrators can see them in the form, masked.
- The secrets reach no entity, state or attribute: only the panel gets them, through `marc2d/subscribe`. Saving the form
  drops whatever an older version left in the options, such as a password nothing uses any more.
- It is kept in the app's private storage, along with the connection, and never in the repo: the
  panel keeps handling calls even if HA is down.
- The presence detector will be added to this response in its delivery.

## Delivery order

1. Time, date, widgets, kiosk mode and answering the intercom.
2. Siren.
3. Turning the screen on with presence.
4. Voice commands.
5. Publishing the camera.

## Updates

A version comes out when someone runs the release workflow on `main`, by hand, as each one ends up offered to the panels.
`convco` works out its number from the commits, and the workflow publishes a GitHub release with its tag, the signed APK,
its hash and the changelog, and nothing else: no stores. Without a `feat` or a `fix` since the last one, it stops.

HA looks for the latest release every 6 hours and shows `update.*_app`. Pressing **Install** sends the panel the APK's URL
and hash. The panel downloads it, checks the hash and installs it without asking, as the Device Owner, and the app starts
by itself afterwards. A panel that is not would need someone to confirm it on its screen, behind the lock screen, so it
does not install it: HA stops showing it in progress and keeps offering it. Android only accepts a newer version signed
with the same key, and that is the protection: the hash comes from the same release, so it only catches a file
corrupted on the way.

The signing key cannot be replaced: changing it means uninstalling the app, which removes the Device Owner, the enrollment
and the kiosk. Keep a copy outside GitHub. Panels that run a debug build need that once, to move to the signed one.

## Home Assistant integration

Our own integration, installable with HACS, in `custom_components/marc2d/`, so that installing marc2d does not require
a broker or writing YAML. It requires HA 2026.10.

- **Enrollment:** the panel is added in HA, which shows a QR code with the internal URL and a one-time code that expires
  in 10 minutes. The panel scans it with the camera, or types both, and exchanges the code at `POST /api/marc2d/enroll`
  for a token. The QR code never carries the token. Each panel gets its own unprivileged, local-only user: removing the
  panel in HA removes that user and revokes its access. The endpoint has no authentication, so 10 wrong codes kill every
  open code.
- **Configuration:** a per-panel form in HA; see [Configuration](#configuration).
- **Entities and doorbell:** see [Entities](#entities) and [Intercom](#intercom).

## Decisions

- Kotlin and Jetpack Compose with Material 3.
- Built with Kotlin Toolchain, without Gradle.
- Linter: ktlint with the Compose rules, through our own plugin in `plugins/ktlint`, because
  the Toolchain does not include one. detekt stays out while 2.0 is still in alpha. The Toolchain does not
  run Android Lint.
- SIP with Linphone SDK, under GPLv3, like marc2d: releases are published with their source. Its commercial license
  would only be needed to distribute the app under other terms.
- HA with Ktor Client, OkHttp engine and kotlinx.serialization: WebSocket and JSON over coroutines.
- Configuration with Jetpack DataStore.
- Wake phrase with Vosk: it needs no Google services nor training for a new phrase, unlike openWakeWord. Its model is
  downloaded, not committed.
- QR codes with CameraX and ZXing core: ML Kit depends on Google services, and CameraX spares us the camera
  lifecycle.
- RTSP with our own small server and the Android APIs (Camera2, MediaCodec, OpenGL), without libraries. RTSP-Server does
  not open its port until the encoder has produced the stream's parameters, so it needs the camera always on, which is
  what we want to avoid, and it comes from a single author on JitPack. Tests run it against the real ffmpeg, which is what
  HA and Frigate use.
- No messages on the screen: what HA has to say, the panel speaks, through its speaker.
- Updates with `PackageInstaller` and GitHub releases, without an app store: the panels have no Google services.
- Audio and screen with the Android APIs, without libraries.
- Photos with `ImageDecoder` and the Ktor client the panel already has, without an image library: it scales them down
  while decoding, turns them upright and reads HEIC too.
- The Ktor server only in tests, to run `HaClient` against a real WebSocket.
- No dependency injection framework: the service creates and connects the pieces by hand.
- Phone calls through Telecom, as the default dialer, with an `InCallService`: the Android API, without libraries, and
  the system keeps the call itself.
- A foreground service keeps the SIP registration and the connection to HA, because the intercom
  does not send push.
- Only stable versions of libraries with recent releases are used. The versions are in
  `libs.versions.toml` and libraries enter the catalog when first used.
