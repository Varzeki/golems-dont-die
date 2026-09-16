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

JAVA="C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot/bin"
ROOT="$(pwd -W 2>/dev/null || pwd)"
B="$ROOT/build"
SLF4J="C:/Users/varzeki/.gradle/caches/modules-2/files-2.1/org.slf4j/slf4j-api/1.7.25/da76ca59f6a57ee3102f8f9bd9cee742973efa8a/slf4j-api-1.7.25.jar"
API="C:/Users/varzeki/.m2/repository/net/runelite/runelite-api/1.12.39-SNAPSHOT/runelite-api-1.12.39-SNAPSHOT.jar"

"$JAVA/javac" -encoding UTF-8 -proc:none -cp "$B/classes/java/main;$SLF4J;$API" \
	-d "$B/probe" dev-tools/probe/com/golemsdontdie/MeshProbe.java
"$JAVA/javac" -encoding UTF-8 -d "$B/devtools2" dev-tools/AuditCurves.java
"$JAVA/java" -cp "$B/devtools2;$B/probe;$B/classes/java/main;$B/resources/main;$SLF4J;$API" \
	AuditCurves "$@" 2>&1 | grep -v '^SLF4J'
