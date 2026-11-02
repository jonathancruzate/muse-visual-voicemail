#!/bin/bash
# Build Muse Voicemail (framework-only Android app, NO AndroidX — the raw
# build therefore cannot hit the KMP empty-jar trap documented in AGENTS.md).
# Toolchain: meta-muse-widget tools/sdk (build-tools 35.0.0, platform
# android-35), used READ-ONLY for compilation, + Temurin JDK 21.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-$HOME/workspace/meta-muse-widget/tools/sdk}"
export JAVA_HOME="$HOME/workspace/custom-skills/local-rea/tools/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
KS="keystore/musevvm.keystore"
KS_PASS_FILE="keystore/keystore-password.txt"
VERSION_CODE=1
VERSION_NAME="1.0.0"

rm -rf build
mkdir -p build/gen build/classes build/dex keystore

if [ ! -f "$KS" ]; then
  PASS="$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)"
  echo "$PASS" > "$KS_PASS_FILE"
  chmod 600 "$KS_PASS_FILE"
  keytool -genkeypair -keystore "$KS" -alias musevvm -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Muse Voicemail, O=JonathanCruzate, C=US" 2>/dev/null
  echo "keystore created"
fi
PASS="$(cat "$KS_PASS_FILE")"

echo "== aapt2 link (manifest only, no res) =="
"$BT/aapt2" link -o build/unsigned.apk \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  --java build/gen \
  --min-sdk-version 29 --target-sdk-version 35 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

echo "== javac =="
find src build/gen -name "*.java" > build/sources.txt
# NOTE: no -bootclasspath — JDK 21 javac + android.jar bootclasspath breaks
# lambdas (android.jar's LambdaMetafactory stub lacks metafactory). Compiling
# against android.jar on the classpath at source 11 works; d8 keeps
# invokedynamic lambdas natively at min-api 29 (runtime has them since 26).
javac -source 11 -target 11 -cp "$ANDROID_JAR" -d build/classes @build/sources.txt

echo "== d8 =="
"$BT/d8" --release --min-api 29 --lib "$ANDROID_JAR" \
  --output build/dex $(find build/classes -name "*.class")

echo "== package =="
cp build/unsigned.apk build/staged.apk
# classes.dex must sit at the APK ROOT — `zip dex/classes.dex` would store
# the dex/ prefix and Android would find no code at all.
(cd build/dex && zip -q ../staged.apk classes.dex)
"$BT/zipalign" -f 4 build/staged.apk build/aligned.apk

echo "== sign =="
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out "Muse-Visual-Voicemail-$VERSION_NAME.apk" build/aligned.apk

echo "== verify =="
"$BT/apksigner" verify --print-certs "Muse-Visual-Voicemail-$VERSION_NAME.apk"
ls -l "Muse-Visual-Voicemail-$VERSION_NAME.apk"
sha256sum "Muse-Visual-Voicemail-$VERSION_NAME.apk"
