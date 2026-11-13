Fire Remote 1.0 - desktop remote for your Fire TV Stick (over ADB)

SETUP
  Put FireRemote.exe in your scrcpy folder (next to adb.exe and scrcpy-server), then run it.
  It finds adb by itself, reconnects to the last IP, and searches your laptop hotspot if the IP changed.
  Windows may warn on first launch (unsigned app): More info > Run anyway.
  Or run build.cmd to compile it yourself from FireRemote.cs.

MODES
  Green dot  = instant: key presses go through scrcpy-server's control channel (no video).
  Amber dot  = basic: adb "input keyevent" (slower). Hover the status to see why instant mode failed.

KEYBOARD (when the remote has focus)
  Arrows = D-pad      Enter = OK (Shift+Enter: long-press)   Esc/Backspace = Back
  H = Home (Shift+H: quick menu)   M = Menu   Space = Play/Pause   [ ] = Rewind / Fast-forward
  + / - = Volume (needs HDMI-CEC)   Mouse wheel = Up/Down   T = type   Ctrl+V = paste text to the TV
  Hold a button: arrows/volume/seek repeat; OK/Home/Back/Menu long-press.

EXTRAS
  Apps   = launch any installed app (e.g. Discord).
  Screen = opens scrcpy for apps that need a mouse.
  Settings are stored in %APPDATA%\FireRemote.

Credits: instant mode drives scrcpy-server by Genymobile (Apache License 2.0), taken from your scrcpy folder.
