#!/usr/bin/env bash
# Production plugin ZIP: Kotlin release flags (no obfuscation).
# Output: build/distributions/*.zip — suitable for JetBrains Marketplace or GitHub Releases.
# ProGuard is still available opt-in via -Pblamely.obfuscate=true if ever needed.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
exec ./gradlew clean buildPlugin --no-daemon \
  -Pblamely.release=true \
  "$@"
