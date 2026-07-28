#!/usr/bin/env bash
# Boots a local Paper 1.21.4 test server with the freshly built MineScripture jar.
# Usage: scripts/run-server.sh   (from the repo root or scripts/)
# Keys come from the environment: MSC_YVP_KEY, MSC_GLOO_ID, MSC_GLOO_SECRET.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="$REPO_DIR/run"

# Load API keys from an untracked .env if present (gitignored; chmod 600).
if [ -f "$REPO_DIR/.env" ]; then
  # shellcheck disable=SC1091
  source "$REPO_DIR/.env"
  echo "Loaded API keys from .env"
fi
PAPER_VERSION="1.21.4"
JAVA_BIN="${JAVA_BIN:-$(ls -d "$REPO_DIR/../tools/jdk-21"*/Contents/Home/bin/java 2>/dev/null | head -1 || echo java)}"

mkdir -p "$RUN_DIR/plugins"

# Download Paper if missing (latest stable build via the v3 Fill API).
if [ ! -f "$RUN_DIR/paper.jar" ]; then
  echo "Fetching Paper $PAPER_VERSION..."
  URL=$(curl -s "https://fill.papermc.io/v3/projects/paper/versions/$PAPER_VERSION/builds/latest" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['downloads']['server:default']['url'])")
  curl -sL -o "$RUN_DIR/paper.jar" "$URL"
fi

# Accept the EULA for the local test server.
echo "eula=true" > "$RUN_DIR/eula.txt"

# Fresh plugin jar.
cp "$REPO_DIR"/build/libs/minescripture-*.jar "$RUN_DIR/plugins/"

cd "$RUN_DIR"
exec "$JAVA_BIN" -Xms1G -Xmx2G -jar paper.jar --nogui
