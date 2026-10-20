#!/usr/bin/env bash
# Linux version of build.ps1: aapt2 -> javac -> d8 -> zipalign -> apksigner, no Gradle.
# Needs a JDK on PATH and an Android SDK with platforms;android-28 and build-tools;34.0.0.
#   ANDROID_SDK_ROOT  SDK location (falls back to ANDROID_HOME, then ../sdk)
#   KEYSTORE          signing key (default ./tvkeys.keystore, created if missing)
#   KEYSTORE_PASSWORD its password (default tvkeys)
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$here/../sdk}}"
bt="$sdk/build-tools/34.0.0"
jar="$sdk/platforms/android-28/android.jar"
ks="${KEYSTORE:-$here/tvkeys.keystore}"
pass="${KEYSTORE_PASSWORD:-tvkeys}"
out="$here/build"

[ -f "$jar" ] || { echo "missing $jar: sdkmanager \"platforms;android-28\"" >&2; exit 1; }
[ -x "$bt/aapt2" ] || { echo "missing $bt: sdkmanager \"build-tools;34.0.0\"" >&2; exit 1; }

rm -rf "$out"
mkdir -p "$out/gen" "$out/classes" "$out/dex"

"$bt/aapt2" compile --dir "$here/res" -o "$out/res.zip"
"$bt/aapt2" link -o "$out/unsigned.apk" -I "$jar" --manifest "$here/AndroidManifest.xml" \
    --java "$out/gen" "$out/res.zip"

find "$here/src" "$out/gen" -name '*.java' > "$out/sources.txt"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -classpath "$jar" -d "$out/classes" @"$out/sources.txt"

find "$out/classes" -name '*.class' > "$out/classes.txt"
"$bt/d8" --release --lib "$jar" --min-api 26 --output "$out/dex" @"$out/classes.txt"

python3 - "$out/unsigned.apk" "$out/dex/classes.dex" <<'EOF'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(sys.argv[2], 'classes.dex')
EOF

"$bt/zipalign" -p -f 4 "$out/unsigned.apk" "$out/aligned.apk"

# Local signing key, kept next to the project so later builds install as updates.
if [ ! -f "$ks" ]; then
    keytool -genkeypair -keystore "$ks" -storepass "$pass" -keypass "$pass" -alias tvkeys \
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=TV Keys'
fi
"$bt/apksigner" sign --ks "$ks" --ks-pass "pass:$pass" --key-pass "pass:$pass" \
    --out "$here/tvkeys.apk" "$out/aligned.apk"
"$bt/apksigner" verify "$here/tvkeys.apk"
echo "built $here/tvkeys.apk"
