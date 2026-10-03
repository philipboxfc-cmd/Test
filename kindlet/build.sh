#!/bin/bash
#
# Builds the KindleNotes Kindlet (native Kindle Keyboard app) and signs it
# with the MobileRead Kindlet Kit test keys.
#
# Needs JDK 8 (javac must emit Java 1.4 class files for the Kindle's CVM).
#   JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 ./build.sh
#
# Output in ../dist/:
#   KindleNotes.azw2            keys regenerated 2025-04 (current MKK), SHA1 signature
#   KindleNotes-sha256.azw2     same keys, SHA-256 signature (like the 2025 KUAL build)
#   KindleNotes-oldkeys.azw2    original 2010 MKK keys (expired 2025-04-17), SHA1
#
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
JAVAP="$JAVA_HOME/bin/javap"
JARSIGNER="$JAVA_HOME/bin/jarsigner"
RT="$JAVA_HOME/jre/lib/rt.jar"
[ -x "$JAVAC" ] || { echo "javac not found in $JAVA_HOME (need JDK 8)" >&2; exit 1; }
"$JAVAC" -version 2>&1 | grep -q '1\.8\.' || echo "warning: JDK 8 expected, got $("$JAVAC" -version 2>&1)" >&2

VERSION=$(sed -n 's/^Implementation-Version: *//p' MANIFEST.MF)
OUT=../dist
rm -rf build
mkdir -p build/stubs build/classes build/jar "$OUT"

echo "== compile KDK stubs"
"$JAVAC" -nowarn -source 1.4 -target 1.4 -bootclasspath "$RT" -d build/stubs $(find kdk-stubs -name '*.java')

echo "== compile KindleNotes (Java 1.4 class files)"
"$JAVAC" -nowarn -encoding UTF-8 -source 1.4 -target 1.4 -bootclasspath "$RT" \
    -cp build/stubs -d build/classes $(find src -name '*.java')

echo "== check bytecode"
for c in $(find build/classes -name '*.class'); do
    major=$("$JAVAP" -v "$c" | sed -n 's/.*major version: *//p' | head -1)
    [ "$major" = "48" ] || { echo "$c: class file major $major, expected 48 (Java 1.4)" >&2; exit 1; }
done
# APIs that do not exist on the Kindle's Java 1.4 class library
forbidden='java/lang/StringBuilder|java/lang/String\.contains|java/lang/String\.isEmpty|java/lang/String\.format|java/lang/String\.replace:\(Ljava/lang/CharSequence|java/lang/Integer\.valueOf:\(I\)|java/lang/Long\.valueOf:\(J\)|java/lang/System\.nanoTime|java/util/concurrent|java/lang/Iterable|java/util/Scanner|java/util/Collections\.empty|java/util/Arrays\.toString|java/lang/Enum|java/lang/annotation'
if "$JAVAP" -c -p $(find build/classes -name '*.class') | grep -E "$forbidden"; then
    echo "found APIs newer than Java 1.4 (see above)" >&2
    exit 1
fi
if "$JAVAP" -c -p $(find build/classes -name '*.class') | grep -E 'com/amazon/kindle/kindlet' | grep -o 'com/amazon/kindle/kindlet[^ ]*' | sort -u > build/kdk-api-used.txt; then
    echo "   KDK API surface: $(wc -l < build/kdk-api-used.txt) distinct references (build/kdk-api-used.txt)"
fi

echo "== jar"
cp -r build/classes/org build/jar/
"$JAR" cfm build/KindleNotes-unsigned.jar MANIFEST.MF -C build/jar .

# Modern JDKs refuse to *verify* SHA1 signatures; the Kindle's 2010-era JVM
# only knows SHA1. Re-enable it for our own verification step only.
printf 'jdk.jar.disabledAlgorithms=MD2, MD5, RSA keySize < 1024\njdk.certpath.disabledAlgorithms=MD2, MD5\n' > build/java.security

sign() { # sign <in.jar> <out.azw2> <keystore> <sigalg> <digestalg>
    local in=$1 out=$2 ks=$3 sigalg=$4 digestalg=$5
    cp "$in" "$out.tmp.jar"
    for alias in dktest ditest dntest; do
        if ! "$JARSIGNER" -keystore "$ks" -storepass password -sigalg "$sigalg" -digestalg "$digestalg" \
                "$out.tmp.jar" "$alias" > build/sign.log 2>&1; then
            cat build/sign.log >&2
            echo "signing $out with $alias failed" >&2
            exit 1
        fi
    done
    mv "$out.tmp.jar" "$out"
    local v
    v=$("$JARSIGNER" -J-Djava.security.properties=build/java.security -verify -verbose:summary -certs "$out" 2>&1 || true)
    if ! echo "$v" | grep -q 'jar verified' || echo "$v" | grep -q -i 'nonexistent\|unsigned\|not signed'; then
        echo "$v" | grep -v 'Picked up' >&2
        echo "verification of $out failed" >&2
        exit 1
    fi
    echo "   $(basename "$out"): $(echo "$v" | grep -c 'Signed by') signatures, $sigalg/$digestalg, verified"
}

echo "== sign"
sign build/KindleNotes-unsigned.jar "$OUT/KindleNotes.azw2"         keys/mkk-2025.keystore SHA1withRSA   SHA1
sign build/KindleNotes-unsigned.jar "$OUT/KindleNotes-sha256.azw2"  keys/mkk-2025.keystore SHA256withRSA SHA-256
sign build/KindleNotes-unsigned.jar "$OUT/KindleNotes-oldkeys.azw2" keys/mkk-2012.keystore SHA1withDSA   SHA1

ls -la "$OUT"/KindleNotes*.azw2
echo "== done (version $VERSION)"
