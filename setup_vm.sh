#!/usr/bin/env bash
set -euo pipefail

mkdir -p ~/group_8
tar --exclude="./.venv" --exclude="__pycache__" -C python -cf - . | tar -C "$HOME/group_8" -xf -
cp ./*.sh output/*.oar ~/group_8/
