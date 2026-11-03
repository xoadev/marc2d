# marc2d

**M**óvil **A**ndroid **R**eciclado **C**omo **C**onsola **D**omótica: MAR + C² + D. In English, a
recycled Android phone turned into a home console.

marc2d turns an old phone, fixed to the wall, into your home's console: clock and weather, video
intercom, cameras, siren and sensors, connected to [Home Assistant](https://www.home-assistant.io/). The
logic stays in Home Assistant: the panel is not the hub.

It's a half-joking personal project, shared in case it's useful to someone.

## What it does

- **Clock and weather** on a black screen, designed for OLED panels that stay on all day.
- **Intercom:** it rings, you answer with audio and video, and you can call it yourself. Tested with a
  Dahua VTO2202F.
- **Cameras** from Home Assistant in full screen, swiping from one to the next.
- **Sensors and siren** in Home Assistant: light, battery and a siren on the alarm channel.
- **A droid**, 16×16 pixels, that wanders around the screen and shows what's going on with the color of
  its eye.

## Status

In testing, with a single panel and no releases. Things still break freely, and the app's code is under
review in a pull request. Next up is a Home Assistant integration to add panels with a QR code.

## What you need

- A phone with Android 15 or later. It's used with LineageOS and without Google services.
- Home Assistant.
- For the intercom, a device that works as a SIP server on your home network.
