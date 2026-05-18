#!/usr/bin/env bash
# Production plugin ZIP: Kotlin release flags + ProGuard obfuscation (see proguard/blamely-release.pro).
# Output: build/distributions/*.zip — suitable for JetBrains Marketplace or GitHub Releases.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
exec ./gradlew clean buildPlugin --no-daemon \
  -Pblamely.release=true \
  -Pblamely.obfuscate=true \
  "$@"
