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

ROOT="$(pwd -W 2>/dev/null || pwd)"
B="$ROOT/build"
# Where the jars are. Each can be set in the environment; otherwise the newest of each is
# taken from the local Gradle and Maven caches.
HOME_DIR="${USERPROFILE:-$HOME}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin}"
JAVA="${JAVA:-$(dirname "$(command -v javac)")}"
SLF4J="${SLF4J_JAR:-$(ls "$HOME_DIR"/.gradle/caches/modules-2/files-2.1/org.slf4j/slf4j-api/*/*/slf4j-api-*.jar 2>/dev/null | tail -1)}"
API="${RUNELITE_API_JAR:-$(ls "$HOME_DIR"/.m2/repository/net/runelite/runelite-api/*/runelite-api-*.jar 2>/dev/null | tail -1)}"
if [ ! -f "$SLF4J" ] || [ ! -f "$API" ]; then
	echo "Set SLF4J_JAR and RUNELITE_API_JAR, or build RuneLite once so they are in the local caches." >&2
	exit 1
fi
PROFILE="${1:-$HOME_DIR/.runelite/profiles2/default-0.properties}"

"$JAVA/javac" -encoding UTF-8 -proc:none -cp "$B/classes/java/main;$SLF4J;$API" \
	-d "$B/probe2" dev-tools/probe/com/golemsdontdie/RoamSim.java
"$JAVA/java" -cp "$B/probe2;$B/classes/java/main;$B/resources/main;$SLF4J;$API" \
	com.golemsdontdie.RoamSim "$PROFILE" "${2:-300}" "${3:-6000}" "${4:-1}" 2>&1 | grep -v '^SLF4J'
