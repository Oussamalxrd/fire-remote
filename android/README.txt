Fire Remote for Android - phase 1 (core remote)

INSTALL
  1. Open FireRemote-Phone.apk on the phone and allow installing from that app when asked.
     If Play Protect warns: More details > Install anyway.
  2. The phone must reach the Fire TV Stick: join the laptop hotspot, or use Tailscale.
  3. Open Fire Remote. The first time, the TV asks "Allow USB debugging?":
     tick "Always allow from this computer", then OK.
  The address defaults to 192.168.137.50. Tap the address at the top to change it.

WHAT IT DOES
  - Instant keys through scrcpy-server (green dot), like the laptop app. Amber = basic mode (slower ADB keys).
  - Stays connected while you switch apps (notification with Disconnect); reconnects by itself
    after drops and network changes. Disconnects after 10 minutes in the background.
  - The phone's volume buttons control the TV while the app is open (... menu to turn off).
  - Hold OK / Back / Home / Menu for a long-press; arrows, volume and seek repeat while held.
  - Apps: list and open the TV's apps. Type: everything typed (or dictated) is mirrored on the TV;
    the send key presses Enter.

NEW IN 1.1
  - Favorites row (up to 6): star apps in the Apps list, or + > Add a website (opens in Sofa TV Browser).
    Long-press a tile to remove it.
  - Touchpad (mouse button next to Apps): a real cursor on the TV. One finger moves, tap clicks,
    two fingers scroll, two-finger tap = Back. To drag: hold Click and move with another finger.
    Needs instant mode (green dot).
  - Notification: Play/pause and Back without opening the app.
  - Power: puts the Stick to sleep, or wakes it.

NEW IN 1.2
  - Settings (... menu): theme (follow phone / dark / light), interface size, language, background time,
    phone volume buttons, vibration, keep screen on, touchpad speed and scroll direction, ADB key reset.
  - Languages: English, French, Arabic (right-to-left), German, Spanish.
  - Bottom corners of the remote: see the TV screen (still or live screenshots) and close the app on the TV.
  - Finds the Stick again by itself if the hotspot gives it a new IP (also "Find the TV" in the address dialog).

REBUILD (only needed for changes)
  Needs JDK 17+ and the Android SDK (platform 36). In this folder: gradlew assembleRelease
  Signing: see "Build it yourself" in the main README (keystore.properties; debug key if absent).
  Android only installs updates signed with the same key, so keep yours.

CREDITS
  scrcpy-server 5.0.1 by Genymobile (Apache License 2.0), bundled for instant keys.
  dadb by mobile.dev (Apache License 2.0), the ADB client.

NEW IN 1.3
  - Real app logos from the Fire TV on favorite tiles and in the Apps list (TV banner or icon).
    They load the first time you open Apps; refresh there after installing new apps on the TV.
  - Settings > Buttons: tap any button on the small remote map to give it another action
    (touchpad, close app, screen peek, Apps, Search, ...) or hide it. Button size slider.
  - D-pad swipe (Settings > Controls): slide anywhere on the D-pad to move like the Amazon app;
    longer slides move more; rest your finger to keep going. Taps still work as before.
  - The logo helper's source is in tvhelper/ (build with tvhelper/build.sh).

NEW IN 1.4
  - ... menu > Remote buttons: the same as the laptop app's More > Remote buttons. Choose what the Fire TV
    remote's Netflix, Prime Video, YouTube, Disney+ and TV buttons do (TV also when held), through FireKeys
    on the Stick: Normal, Last app, Sleep, any TV app (with its logo) or a link. Each pick is saved on the
    TV at once and read back. The status line shows whether FireKeys is running, and warns if Sleep is
    set but FireKeys isn't device admin yet.
