#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_DIR="${SCRIPT_DIR}/java"
OUTPUT_DIR="${SCRIPT_DIR}/output"

echo "Installing maven"
sudo apt install maven

echo "Building ONOS app in ${JAVA_DIR}"
mvn -f "${JAVA_DIR}/pom.xml" clean install -DskipTests "$@"

shopt -s nullglob
oar_files=("${JAVA_DIR}"/target/*.oar)
shopt -u nullglob

if [[ ${#oar_files[@]} -eq 0 ]]; then
    echo "ERROR: no .oar file found in ${JAVA_DIR}/target" >&2
    exit 1
fi

mkdir -p "${OUTPUT_DIR}"
for oar in "${oar_files[@]}"; do
    cp -v "${oar}" "${OUTPUT_DIR}/"
done

echo "OAR file(s) copied to ${OUTPUT_DIR}"
