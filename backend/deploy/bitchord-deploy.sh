#!/usr/bin/env bash
# Pulls main and rebuilds. The GitHub deploy key is pinned to this script
# (forced command), so a leaked key can do nothing but redeploy main.
set -euo pipefail
SRC=/opt/lossless-src
if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch main https://github.com/daddyyour0786-dev/Lossless.git "$SRC"
fi
git -C "$SRC" fetch --depth 1 origin main
git -C "$SRC" reset --hard FETCH_HEAD
echo "deploying $(git -C "$SRC" log -1 --format='%h %s')"
DOMAIN="$(cat /etc/lossless-domain)" bash "$SRC/backend/deploy/setup.sh"
