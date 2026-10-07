#!/usr/bin/env bash
# Production plugin ZIP: Kotlin release flags. Unobfuscated by design — Blamely
# is open source, and the ProGuard machinery was removed from build.gradle.kts.
# Output: build/distributions/*.zip — suitable for JetBrains Marketplace or GitHub Releases.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
exec ./gradlew clean buildPlugin --no-daemon \
  -Pblamely.release=true \
  "$@"
