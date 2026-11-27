#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mvn -B -f java/pom.xml -DskipTests package

JARS="$(find "$HOME/.m2/repository" \
    -type f -name '*.jar' -printf '%p:')"

CP="$ROOT/java/target/classes:$JARS"

javac -cp "$CP" \
    -d "$TMP" \
    java/tests/DataPlaneRateTest.java \
    java/tests/BabblingIntegrationTest.java

# Detector algorithm first, then detector with isolation and reinstatement.
java -cp "$TMP:$CP" DataPlaneRateTest
java -cp "$TMP:$CP" BabblingIntegrationTest
