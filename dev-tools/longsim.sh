#!/bin/sh
# Runs LongSim across several processes and leaves the shards for dev-tools/longsim.py.
#
# Needs the plugin compiled (gradlew classes). The classes are copied out first, so the run is
# unaffected by anything built while it is going, and each shard runs detached at Idle priority:
# a week of simulated time takes hours beside whatever else the machine is doing.
#
# The copy goes somewhere without spaces in the path. The plugin's own directory has two, and a
# classpath with a space in it reaches java as three arguments however it is quoted on the way.
#
#   sh dev-tools/longsim.sh [golems-per-shard] [ticks] [shards] [tag]
#   python dev-tools/longsim.py $TEMP/golem-longsim/<tag>-*.csv

set -e
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1

GOLEMS="${1:-250}"
TICKS="${2:-1008000}"
SHARDS="${3:-8}"
TAG="${4:-week}"
SNAPSHOT_TICKS=6000

B="$(pwd -W 2>/dev/null || pwd)/build"
HOME_DIR="${USERPROFILE:-$HOME}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin}"
JAVA="${JAVA:-$(dirname "$(command -v javac)")}"
SLF4J="${SLF4J_JAR:-$(ls "$HOME_DIR"/.gradle/caches/modules-2/files-2.1/org.slf4j/slf4j-api/*/*/slf4j-api-*.jar 2>/dev/null | tail -1)}"
API="${RUNELITE_API_JAR:-$(ls "$HOME_DIR"/.m2/repository/net/runelite/runelite-api/*/runelite-api-*.jar 2>/dev/null | grep -v -- '-sources\|-javadoc\|-runtime' | tail -1)}"
PROFILE="${PROFILE:-$HOME_DIR/.runelite/profiles2/default-0.properties}"
WORK="${TEMP:-$HOME_DIR/AppData/Local/Temp}/golem-longsim"

if [ ! -f "$SLF4J" ] || [ ! -f "$API" ]; then
	echo "Set SLF4J_JAR and RUNELITE_API_JAR, or build RuneLite once so they are in the local caches." >&2
	exit 1
fi

SNAP="$WORK/classes"
rm -rf "$SNAP"
mkdir -p "$SNAP"
cp -r "$B/classes/java/main/." "$SNAP/"
cp -r "$B/resources/main/." "$SNAP/"
cp "$SLF4J" "$WORK/slf4j.jar"
cp "$API" "$WORK/runelite-api.jar"
rm -f "$WORK/$TAG"-*.csv "$WORK/$TAG"-*.log "$WORK/$TAG"-*.err

CP="$SNAP;$WORK/slf4j.jar;$WORK/runelite-api.jar"
"$JAVA/javac" -encoding UTF-8 -proc:none -cp "$CP" -d "$SNAP" dev-tools/probe/com/golemsdontdie/LongSim.java dev-tools/probe/com/golemsdontdie/RoamSim.java

echo "$SHARDS shards of $GOLEMS golems, $TICKS ticks each, into $WORK"
powershell -NoProfile -ExecutionPolicy Bypass -File dev-tools/longsim-launch.ps1 	-Java "$JAVA/java.exe" -Work "$WORK" -Cp "$CP" -Profile "$PROFILE" 	-Tag "$TAG" -Shards "$SHARDS" -Golems "$GOLEMS" -Ticks "$TICKS" -SnapshotTicks "$SNAPSHOT_TICKS"

echo "Merge with:  python dev-tools/longsim.py '$WORK/$TAG'-*.csv"
