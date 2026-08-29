#!/bin/bash
# Sopotnik gateway — posodobi in zaženi (poverilnice bere iz backend/.env)
set -e
cd "$(dirname "$0")"
git pull --ff-only || echo "⚠ git pull ni uspel — nadaljujem z obstoječo kodo"
npm install --no-audit --no-fund --silent
exec node server.mjs
