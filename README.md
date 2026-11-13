# Fire Remote

Control a Fire TV Stick from your **Windows laptop** or your **Android phone** over ADB. Key presses are instant, and you also get a real mouse cursor on the TV, typing, app logos and favorites.

> Independent project, not affiliated with Amazon.

| Folder | App | Built with |
|---|---|---|
| [`desktop/`](desktop) | Windows remote (one `.exe`) | C# WinForms, .NET Framework 4.x |
| [`android/`](android) | Phone remote | Kotlin, Jetpack Compose |

Downloads are on the [Releases](../../releases/latest) page.

## Features

**Both apps**
- **Instant mode (green dot).** Keys go through [scrcpy-server](https://github.com/Genymobile/scrcpy)'s control channel, with no video. If that fails, the app falls back to `adb input` (amber dot).
- **Remote keys.** D-pad, OK, Back, Home, Menu, play/pause, rewind and fast-forward. Holding a button repeats it or long-presses it.
- **Typing.** Type, paste or dictate text and it appears on the TV.
- **Mouse.** A real cursor on the TV for websites and apps that need one.
- **Apps and favorites.** Launch any installed app. Website tiles open in Sofa TV Browser.
- **Reconnects.** Finds the Stick again if your hotspot gives it a new IP.
- **Remote buttons.** Choose what the Fire TV remote's Netflix / Prime Video / YouTube / Disney+ / TV buttons do. This needs the FireKeys companion app on the Stick, which is not part of this repo.

**Desktop**
- Keyboard control: arrows, Enter, Esc, Space, `[` `]`, `T` to type, and Ctrl+V to paste to the TV.
- Ctrl+Alt+M turns the TV mouse on and off from any window.
- Shows the TV screen through scrcpy.

**Android**
- Stays connected in the background through a foreground service, and reconnects after drops and network changes.
- Touchpad: one finger moves, tap clicks, two fingers scroll.
- D-pad swipe like the Amazon app.
- Notification controls, and the phone's volume buttons control the TV.
- Shows the real app logos from the TV on favorites and in the Apps list.
- Every button can be configured or hidden, and button size is adjustable.
- Screen peek (still or live screenshots), and close the app running on the TV.
- Power button puts the Stick to sleep or wakes it.
- Theme follows the phone or is set to dark or light. Interface size is adjustable.
- Languages: English, French, Arabic (right-to-left), German and Spanish.

## Requirements

- **Fire TV Stick with ADB debugging on.**
  1. Settings > My Fire TV > About: select the device name 7 times to unlock Developer options.
  2. Developer options > ADB debugging: on.
- **The TV and the controller on the same network,** for example your laptop's mobile hotspot. The default address is `192.168.137.50`, from the Windows hotspot range, and you can change it in either app. From another network, a Tailscale subnet route works.
- **Desktop:** Windows with .NET Framework 4.x (built into Windows 10/11), plus [scrcpy](https://github.com/Genymobile/scrcpy/releases) for `adb.exe` and `scrcpy-server`.
- **Android:** Android 8.0 or later.

## Install

**Desktop**
1. Put `FireRemote.exe` in your scrcpy folder, next to `adb.exe` and `scrcpy-server`.
2. Run it. Windows may warn because the app is unsigned: choose More info > Run anyway.

**Android**
1. Install `FireRemote-Phone-<version>.apk`, allowing installs from your browser or file manager.
2. The first time it connects, the TV asks *Allow USB debugging?* Tick *Always allow*, then OK.

## Build it yourself

**Desktop.** Run `desktop\build.cmd`. It uses the C# compiler that ships with Windows, so you don't need Visual Studio.

**Android.** You need JDK 17+ and the Android SDK (platform 36). Set `ANDROID_HOME`, or create `android/local.properties` with `sdk.dir=...`.

```sh
cd android
./gradlew testReleaseUnitTest assembleRelease     # Windows: gradlew.bat ...
```

Release signing reads `android/keystore.properties`, which is gitignored and never committed:

```properties
storeFile=signing/my-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file, release builds are signed with your debug key. The APK on the Releases page is signed with the author's key. Android won't install an APK signed with a different key over it, so uninstall the app first.

**TV logo helper.** Run `ANDROID_HOME=... sh android/tvhelper/build.sh`. It rebuilds `android/app/src/main/assets/fireremote-icons.jar`, a small helper that runs on the TV with `app_process` and sends back app banners and icons as PNGs.

## How it works

1. The app connects to the Stick with ADB over TCP (port 5555). The desktop app uses `adb.exe`; the phone uses [dadb](https://github.com/mobile-dev-inc/dadb), a Kotlin ADB client with its own key.
2. It pushes `scrcpy-server` to `/data/local/tmp` and starts it with video and audio off.
3. It sends scrcpy control messages: key events, text, clipboard paste, app launch, and a virtual UHID mouse.

## Limits

- **Volume and turning the TV off** go through HDMI-CEC, which older TVs may ignore. The power button always sleeps the Stick.
- **The mouse and touchpad** need instant mode.
- **Website tiles** need Sofa TV Browser on the TV.

## License

[MIT](LICENSE) © 2026 Oussama Lamaizi.

Bundled and linked open-source components (scrcpy-server, dadb, AndroidX) keep their Apache 2.0 licenses: see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Fire TV and Amazon are trademarks of Amazon.com, Inc. or its affiliates. This project is not affiliated with, endorsed by or sponsored by Amazon.
