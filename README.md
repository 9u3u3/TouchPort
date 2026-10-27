# TouchPort

**Play PC games with mobile-style touch controls.**
TouchPort turns an Android phone into a customisable touch HUD (floating joystick, drag-to-aim,
coloured buttons, gyro aim) for keyboard-and-mouse games on a Linux PC.
If your hands know mobile shooters, you can play PC shooters the same way.

```
Android app (touch HUD) ──USB (ADB) or Wi-Fi──► touchport-receiver ──► virtual keyboard + mouse ──► any game
```

The game only sees a normal keyboard and mouse. TouchPort sends exactly what you touch:
**no macros, no auto-fire, no aim assistance.**

## Features

- **Mobile-style controls:** floating joystick (8-way, auto-sprint), drag anywhere on the right side to aim,
  drag on Fire / ADS to aim while shooting.
- **Gyro aim:** always on, only while aiming, or while firing or aiming. Yaw or roll, adjustable sensitivity.
- **Fully customisable HUD:** move, resize, recolour, relabel and rebind every button. Hold or toggle.
- **Profiles:** one layout per game. Presets for a generic FPS and for The Finals. Share profiles as text.
- **Low latency:** USB (ADB) about 1 ms, Wi-Fi 2–5 ms. Touches are sent as they happen (no per-frame
  batching on Android 11+), on a high-priority thread, with Wi-Fi in low-latency mode.
- **Safe:** every packet carries the full state, so a lost packet never leaves a key stuck. Keys release
  when the app closes or the connection drops for 500 ms.
- **Small:** the app has no libraries (about 90 KB). The receiver is one C file with no dependencies.

## Requirements

- **PC:** Linux with `/dev/uinput` (any modern distribution). Windows and macOS are not supported yet.
- **Phone:** Android 9 or newer. A gyroscope for gyro aim.
- **For USB:** `adb` on the PC and USB debugging on the phone.

## Quick start

### 1. PC: build and run the receiver

```sh
git clone https://github.com/9u3u3/TouchPort.git
cd TouchPort
./start.sh
```

`start.sh` builds the receiver (needs `gcc` and `make`), opens the app on the phone when it is plugged in,
and cleans up when you press Ctrl+C.

The receiver needs write access to `/dev/uinput`. If it says `Permission denied`, allow your user:

```sh
echo 'KERNEL=="uinput", MODE="0660", GROUP="input", OPTIONS+="static_node=uinput"' | sudo tee /etc/udev/rules.d/60-touchport-uinput.rules
sudo usermod -aG input "$USER"   # then log out and in again
sudo udevadm control --reload && sudo udevadm trigger
```

### 2. Phone: install the app

Download the APK from the [Releases](https://github.com/9u3u3/TouchPort/releases) page, or build it:

```sh
cd android
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

### 3. Connect

| Mode | How | Delay |
|---|---|---|
| **USB (ADB)**, recommended | Turn on USB debugging, plug in, run the receiver. The app uses it automatically. | ~1 ms |
| **Wi-Fi** | Same network. The app finds the PC by itself. | 2–5 ms |
| **USB tethering** | Turn on USB tethering on the phone. Works like Wi-Fi, over the cable. | ~1 ms |
| **Manual IP** | Gear → Settings → Connection, if your router blocks discovery. | 2–5 ms |

The bottom line of the HUD shows the profile, the connection and the measured round-trip time.

**Wi-Fi and tethering need UDP port 47800 open on the PC.** If you use a firewall, allow it for your
local network only, for example with nftables:

```sh
sudo nft add rule inet filter input ip saddr 192.168.1.0/24 udp dport 47800 accept
```

USB with ADB does not need any firewall change.

Note: the phone's touch screen itself adds about 15–40 ms. No connection can remove that.

## Using the app

Tap the **gear** (top left):

- **Edit layout:** drag controls to move them. Tap a control to change its label, key, colour, size,
  opacity, hold or toggle, and drag-to-aim. **+ Add** adds a button. **Reset** loads a preset. **Done** saves.
- **Profiles:** switch games, make a new profile from a preset or a copy, rename, delete,
  **share** a profile as text, or **import** one from the clipboard.
- **Settings:** connection mode, touch sensitivity, gyro mode and sensitivity, joystick area, vibration.

Tips:

- Set sensitivity in the app first, then fine-tune with the game's own sensitivity.
- If aim feels uneven, turn off mouse acceleration in your desktop (for example `accel-profile "flat"`).
- Back does nothing during play, so a stray swipe cannot close the HUD. Press Home to leave.

## Keys

Buttons can press any of: mouse left / right / middle / 4 / 5, wheel up / down, A–Z, 0–9, F1–F9,
Space, Shift, Ctrl, Alt, Tab, Esc, Enter, Caps Lock, Backspace, `` ` ``, arrow keys,
and the special "Gyro on / off". The joystick sends W A S D (or the arrow keys) plus a sprint key.

## Presets

| Preset | For |
|---|---|
| Generic FPS | Most shooters with standard WASD / mouse bindings |
| The Finals | The Finals' default PC bindings (gadgets, specialization, ping, …) |
| Blank | Only a joystick: build your own |

Made a good layout for a game? Share it (gear → Profiles → Manage → Share) and open an issue or a
pull request so others can use it.

## Anti-cheat

TouchPort creates a standard virtual keyboard and mouse through the Linux kernel (`uinput`), the same
mechanism that many input tools use. It does not touch game files or memory. It cannot promise that
every anti-cheat accepts virtual input devices: check the rules of the game you play.

## How it works

- `receiver/touchport-receiver.c` creates two `uinput` devices (keyboard and mouse) and listens on
  port 47800: UDP on all interfaces (Wi-Fi, tethering) and TCP on localhost (USB through `adb reverse`,
  which it sets up by itself).
- The app sends a 32-byte packet on every change and every 50 ms. Packet layout (little-endian):

| Offset | Size | Field |
|---|---|---|
| 0 | 2 | magic `"TP"` |
| 2 | 1 | version (1) |
| 3 | 1 | type: 1 state, 2 discover, 3 discover reply, 4 bye, 5 ping, 6 pong |
| 4 | 4 | sequence number |
| 8 | 8 | held keys, a bit mask (see `KEYMAP` in the receiver and `Keys.kt`) |
| 16 | 12 | cumulative mouse X, mouse Y, wheel (i32 each) |
| 28 | 4 | session id |

Discovery replies are 64 bytes with the PC's host name at offset 32.

## Project layout

```
receiver/        Linux receiver (C, no dependencies)
android/         Android app (Kotlin, no libraries)
start.sh         Builds and runs the receiver, opens the app on a plugged-in phone
```

## Roadmap ideas

- Windows receiver
- Virtual gamepad mode for controller-only games
- More presets

Contributions are welcome.

## License

[MIT](LICENSE)

TouchPort is not affiliated with Embark Studios or any game it can be used with.
