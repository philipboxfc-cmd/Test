#!/bin/bash
# Desktop tests of the kindlet (needs ./build.sh first):
#   StoreTest      storage layer (titles, search, translit, atomic writes)
#   LifecycleTest  headless create/start/stop and screen navigation on the KDK stubs
set -euo pipefail
cd "$(dirname "$0")"
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}
mkdir -p build/test
"$JAVA_HOME/bin/javac" -nowarn -encoding UTF-8 -cp build/classes:build/stubs -d build/test test/StoreTest.java test/LifecycleTest.java
"$JAVA_HOME/bin/java" -cp build/classes:build/stubs:build/test StoreTest
# the empty assistive_technologies property stops Ubuntu's JDK from loading GNOME accessibility in headless mode
"$JAVA_HOME/bin/java" -Djava.awt.headless=true -Djavax.accessibility.assistive_technologies= \
    -cp build/classes:build/stubs:build/test LifecycleTest
