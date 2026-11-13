#!/bin/sh
# Builds app/src/main/assets/fireremote-icons.jar (the TV-side logo helper). Needs a JDK and the Android SDK.
# Usage: ANDROID_HOME=/path/to/sdk sh tvhelper/build.sh
set -e
cd "$(dirname "$0")"
JAR="$ANDROID_HOME/platforms/android-36/android.jar"
D8="$(ls -d "$ANDROID_HOME"/build-tools/* | tail -1)/d8"
rm -rf out && mkdir -p out/classes
javac -source 8 -target 8 -bootclasspath "$JAR" -nowarn -d out/classes src/app/fireremote/tvhelper/IconDump.java
"$D8" --min-api 22 --lib "$JAR" --output out/helper.zip $(find out/classes -name '*.class')
cp out/helper.zip ../app/src/main/assets/fireremote-icons.jar
rm -rf out
echo "built ../app/src/main/assets/fireremote-icons.jar"
