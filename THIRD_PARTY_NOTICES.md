# Third-party notices

Fire Remote uses the following open-source components. They are not covered by
this repository's MIT license; each keeps its own license, reproduced in
[`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt).

## scrcpy-server 5.0.1

- Project: https://github.com/Genymobile/scrcpy
- Copyright (C) 2018 Genymobile, Copyright (C) 2018-2025 Romain Vimont
- License: Apache License 2.0
- Use:
  - **Android app:** the unmodified server binary is bundled at
    `android/app/src/main/assets/scrcpy-server` and pushed to the TV for instant
    key presses, text input and the touchpad mouse.
  - **Desktop app:** it uses the copy in your own scrcpy folder; nothing is bundled.

## dadb 2.0.0

- Project: https://github.com/mobile-dev-inc/dadb
- Copyright mobile.dev Inc.
- License: Apache License 2.0
- Use: the ADB client library in the Android app. Gradle downloads it from Maven
  Central, and it is compiled into the APK.

## AndroidX, Jetpack Compose, Kotlin

- Copyright The Android Open Source Project and JetBrains s.r.o.
- License: Apache License 2.0
- Use: downloaded by Gradle and compiled into the APK.

## Trademarks

Fire TV, Fire TV Stick and Amazon are trademarks of Amazon.com, Inc. or its
affiliates. Fire Remote is an independent project, not affiliated with, endorsed
by, or sponsored by Amazon.
