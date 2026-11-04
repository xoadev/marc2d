# marc2d design

How the app should look and behave. The scope is in [SPEC.md](SPEC.md); if a change contradicts this
document, update it in the same commit.

## Principles

- **It is furniture, not a phone.** The panel is on the wall all day: it should not draw attention until
  something matters.
- **It reads at a glance and from a distance.** Large type, few elements and wide buttons.
- **The OLED rules.** Pure black background, and nothing fixed on screen for long.
- **Inspiration:** 90s sprites and arcade machines, without decorative nostalgia.

## Color

| Use               | Color  | Value     |
| ----------------- | ------ | --------- |
| Background        | black  | `#000000` |
| Time, head        | white  | `#D8D8D8` |
| Date, base, text  | gray   | `#A0A0A0` |
| Warning           | yellow | `#F0C020` |
| Danger, recording | red    | `#E02020` |

Color means something: yellow and red only appear for a warning or a danger. No color for
decoration. Weather symbols are monochrome, never color emoji.

## The droid

It is the app's mascot and status indicator, as well as its icon. It is a 16×16 pixel sprite, with a
white head, a compact gray base and one eye.

- **Drawn in pixels:** no antialiasing, gradients or rotations.
- **Moves in jumps:** one step every half second, with two base frames. No smooth animation.
- **Takes up 10% of the width** of the screen and walks along the bottom strip.
- **Stays where it is** when its state changes: the state is read from the eye, not the position.
- **Asleep** it does not move, except every 20 minutes, when it walks to another spot and goes back to sleep.
- **It is original:** it recalls an arcade droid, but must not look like any known character.

The states and what triggers them are in [SPEC.md](SPEC.md#droid).

| State     | Eye                                                    | Movement         |
| --------- | ------------------------------------------------------ | ---------------- |
| Walking   | black with a glint, looks where it is going            | walks and blinks |
| Sleeping  | closed line                                            | still            |
| Offline   | an X                                                   | still            |
| Alarm     | yellow                                                 | blinks           |
| Intruder  | red band                                               | blinks           |
| Recording | lens with a red center                                 | blinks           |
| Listening | large, with a glint, and a microphone drawn at the top | blinks           |

## Buttons

Square, with straight edges and a black background, with a 12×12 pixel icon from the same family as the droid.
No rounded Material buttons. Use an icon only if it is clear without text: if not, draw a better one,
or add a word. They are white, and red is reserved for **Hang up**.

## Screens

- **Main:** time, date, weather and droid, with no buttons. Tapping anywhere opens the menu.
- **Menu:** large buttons on black. It closes with the X, by tapping outside, and on its own after 10 seconds.
- **Settings:** black background, straight-edged fields and text buttons with the same shape as the rest. It
  closes with the X.
- **Cameras:** full screen, swiping from one to the next. If one can be called, it shows
  **Talk**. Exit with the X, by tapping the image, or automatically after 2 minutes.
- **Call:** the video fills the whole screen. **Hang up** never moves and sits in the corner
  opposite **Answer**, so that a double tap does not end the call.
- **Orientation:** always landscape.

## Text

In English, with short sentences and no technical jargon on screen: "No connection to Home Assistant" is the limit. Technical
detail goes in the settings, not on the main screen.
