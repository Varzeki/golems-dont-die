#!/bin/sh
# Runs the obstacle validator against the newest journal and the live config.
#
# Needs the plugin compiled (gradlew compileJava processResources): the walkability checks
# load the plugin's own mesh classes rather than re-implementing them. compileJava does not
# touch the jar, so this is safe while the dev client is running.
#
#   sh dev-tools/audit.sh [profile.properties] [journal.tsv]

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

"$JAVA/javac" -encoding UTF-8 -proc:none -cp "$B/classes/java/main;$SLF4J;$API" \
	-d "$B/probe" dev-tools/probe/com/golemsdontdie/MeshProbe.java
"$JAVA/javac" -encoding UTF-8 -d "$B/devtools2" dev-tools/AuditCurves.java
"$JAVA/java" -cp "$B/devtools2;$B/probe;$B/classes/java/main;$B/resources/main;$SLF4J;$API" \
	AuditCurves "$@" 2>&1 | grep -v '^SLF4J'
