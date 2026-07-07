#!/bin/bash
# Inject signaling and TURN secrets from local.properties into source before building.
# Works identically for local dev (Android Studio) and CI (GitHub Actions).
#
# Usage:
#   ./scripts/inject-local-secrets.sh              # inject from local.properties
#   ./scripts/inject-local-secrets.sh reset         # revert source to defaults
#   ./scripts/inject-local-secrets.sh --props FILE  # inject from custom properties file
#
# local.properties keys (all optional):
#   signaling.auth.token=<token>
#   signaling.server.url=wss://spectacle.teachmint.qa/ws
#   turn.urls=turn:turn1.teachmint.com:3478
#   turn.username=<username>
#   turn.credential=<password>

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SIGNALING_CONFIG="$PROJECT_ROOT/composeApp/src/commonMain/kotlin/com/teachmint/sharex/share/shared/RemoteServerConfig.kt"
ICE_CONFIG="$PROJECT_ROOT/composeApp/src/commonMain/kotlin/com/teachmint/sharex/share/shared/IceServerConfig.kt"
LOCAL_PROPS="$PROJECT_ROOT/local.properties"

# Parse arguments
if [ "$1" = "reset" ]; then
    cd "$PROJECT_ROOT"
    git checkout "$SIGNALING_CONFIG" "$ICE_CONFIG" 2>/dev/null || true
    echo "Reset config files to defaults."
    exit 0
fi

if [ "$1" = "--props" ] && [ -n "$2" ]; then
    LOCAL_PROPS="$2"
fi

if [ ! -f "$SIGNALING_CONFIG" ] || [ ! -f "$ICE_CONFIG" ]; then
    echo "ERROR: Config files not found. Expected at: $PROJECT_ROOT"
    exit 1
fi

if [ ! -f "$LOCAL_PROPS" ]; then
    echo "WARNING: $LOCAL_PROPS not found. No secrets injected."
    exit 0
fi

# Helper: read a property value from the file (tolerates leading whitespace from YAML heredocs)
read_prop() {
    grep "^[[:space:]]*$1=" "$LOCAL_PROPS" 2>/dev/null | head -1 | sed "s/^[[:space:]]*$1=//" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

TOKEN=$(read_prop "signaling.auth.token")
URL=$(read_prop "signaling.server.url")
TURN_URLS=$(read_prop "turn.urls")
TURN_USER=$(read_prop "turn.username")
TURN_CRED=$(read_prop "turn.credential")

CHANGED=0

# Detect sed -i syntax (macOS vs Linux)
if sed --version 2>/dev/null | grep -q GNU; then
    SED_I="sed -i"
else
    SED_I="sed -i ''"
fi

do_sed() {
    if sed --version 2>/dev/null | grep -q GNU; then
        sed -i "$@"
    else
        sed -i '' "$@"
    fi
}

# F-006: Escape sed metacharacters to prevent code injection via local.properties / CI env vars.
escape_sed() {
    printf '%s' "$1" | sed -e 's/[&\\/|]/\\&/g' -e 's/"/\\"/g'
}

# --- Signaling config ---
if [ -n "$TOKEN" ]; then
    SAFE_TOKEN=$(escape_sed "$TOKEN")
    do_sed "s|DEFAULT_SIGNALING_AUTH_TOKEN: String = \"\"|DEFAULT_SIGNALING_AUTH_TOKEN: String = \"$SAFE_TOKEN\"|" "$SIGNALING_CONFIG"
    echo "Injected auth token: ${TOKEN:0:8}..."
    CHANGED=1
fi

if [ -n "$URL" ]; then
    SAFE_URL=$(escape_sed "$URL")
    do_sed "s|const val DEFAULT_REMOTE_SERVER_URL: String = \"wss://spectacle.teachmint.qa/ws\"|const val DEFAULT_REMOTE_SERVER_URL: String = \"$SAFE_URL\"|" "$SIGNALING_CONFIG"
    echo "Injected server URL: $URL"
    CHANGED=1
fi

# --- TURN config ---
if [ -n "$TURN_URLS" ]; then
    SAFE_TURN_URLS=$(escape_sed "$TURN_URLS")
    do_sed "s|DEFAULT_TURN_URLS: String = \"\"|DEFAULT_TURN_URLS: String = \"$SAFE_TURN_URLS\"|" "$ICE_CONFIG"
    echo "Injected TURN URLs: $TURN_URLS"
    CHANGED=1
fi

if [ -n "$TURN_USER" ]; then
    SAFE_TURN_USER=$(escape_sed "$TURN_USER")
    do_sed "s|DEFAULT_TURN_USERNAME: String = \"\"|DEFAULT_TURN_USERNAME: String = \"$SAFE_TURN_USER\"|" "$ICE_CONFIG"
    echo "Injected TURN username: $TURN_USER"
    CHANGED=1
fi

if [ -n "$TURN_CRED" ]; then
    SAFE_TURN_CRED=$(escape_sed "$TURN_CRED")
    do_sed "s|DEFAULT_TURN_CREDENTIAL: String = \"\"|DEFAULT_TURN_CREDENTIAL: String = \"$SAFE_TURN_CRED\"|" "$ICE_CONFIG"
    echo "Injected TURN credential: ${TURN_CRED:0:4}..."
    CHANGED=1
fi

if [ "$CHANGED" -eq 0 ]; then
    echo "No secrets found in $LOCAL_PROPS."
else
    echo ""
    echo "Done. Secrets injected into source."
fi
