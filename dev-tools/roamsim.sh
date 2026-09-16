#!/bin/sh
# Simulates golems out of view with the plugin's own planner and reports how far into
# dungeons and up buildings they get. See dev-tools/probe/com/golemsdontdie/RoamSim.java.
#
# Needs the plugin compiled (gradlew classes). Safe while the dev client is running.
#
#   sh dev-tools/roamsim.sh [profile.properties] [golems] [ticks] [seed]

set -e
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1

JAVA="C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot/bin"
ROOT="$(pwd -W 2>/dev/null || pwd)"
B="$ROOT/build"
SLF4J="C:/Users/varzeki/.gradle/caches/modules-2/files-2.1/org.slf4j/slf4j-api/1.7.25/da76ca59f6a57ee3102f8f9bd9cee742973efa8a/slf4j-api-1.7.25.jar"
API="C:/Users/varzeki/.m2/repository/net/runelite/runelite-api/1.12.39-SNAPSHOT/runelite-api-1.12.39-SNAPSHOT.jar"
PROFILE="${1:-C:/Users/varzeki/.runelite/profiles2/default-0.properties}"

"$JAVA/javac" -encoding UTF-8 -proc:none -cp "$B/classes/java/main;$SLF4J;$API" \
	-d "$B/probe" dev-tools/probe/com/golemsdontdie/RoamSim.java
"$JAVA/java" -cp "$B/probe;$B/classes/java/main;$B/resources/main;$SLF4J;$API" \
	com.golemsdontdie.RoamSim "$PROFILE" "${2:-300}" "${3:-6000}" "${4:-1}" 2>&1 | grep -v '^SLF4J'
