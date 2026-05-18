#!/usr/bin/env bash
# Start IntelliJ IDEA Community (see build.gradle.kts) with THIS repo's Blamely plugin in the Gradle sandbox.
#
# IMPORTANT
# - Only the IDE process launched by Gradle has Blamely from this checkout. Opening your normal
#   IntelliJ app will NOT load this dev plugin unless you install build/distributions/*.zip by hand.
# - GitHub Copilot is NOT bundled with IntelliJ. Default sandbox only adds Git + Blamely. Use
#   --copilot to fetch Copilot from JetBrains Marketplace into the sandbox (needs network).
#
# WHY PLUGINS DISAPPEAR ON EACH RUN
# Gradle prepareSandbox syncs the sandbox plugins folder from this build only. Plugins installed
# manually inside the sandbox IDE are removed next run. Use --copilot every time, declare plugins
# in build.gradle.kts, or unpack them under sandbox-extra-plugins/ (see README.txt there).
#
# Usage:
#   ./run-sandbox.sh                    # Minimal IC sandbox: Git4Idea + Blamely (fast)
#   ./run-sandbox.sh --copilot        # Also installs GitHub Copilot into the sandbox (sign-in inside IDE)
#   ./run-sandbox.sh --full           # All bundled IC 2023.2 plugins from sandbox-full-plugins.txt
#   ./run-sandbox.sh --full --copilot # Full IDE + Copilot + Blamely
#   ./run-sandbox.sh --no-daemon      # Extra Gradle flags pass through after stripping flags below
#
# Gradle equivalents:
#   -Pblamely.sandbox.full=true
#   -Pblamely.sandbox.copilot=true
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

sandbox_full=false
sandbox_copilot=false
filtered=()
for arg in "$@"; do
  case "$arg" in
    --full)
      sandbox_full=true
      ;;
    --copilot)
      sandbox_copilot=true
      ;;
    *)
      filtered+=("$arg")
      ;;
  esac
done

if ((${#filtered[@]} > 0)); then
  set -- "${filtered[@]}"
else
  set --
fi

gradle_props=()
[[ "$sandbox_full" == true ]] && gradle_props+=("-Pblamely.sandbox.full=true")
[[ "$sandbox_copilot" == true ]] && gradle_props+=("-Pblamely.sandbox.copilot=true")

if ((${#gradle_props[@]} > 0)); then
  exec ./gradlew "${gradle_props[@]}" runIde "$@"
else
  exec ./gradlew runIde "$@"
fi
