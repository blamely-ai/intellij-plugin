#!/usr/bin/env bash
set -e
# Default: fast plugin ZIP (no ProGuard). For production / GitHub Releases use ./release-build.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RESOURCES="$SCRIPT_DIR/src/main/resources"
ICON_SOURCE="$SCRIPT_DIR/images/icon.png"

# Generate resized icons from source image if missing
if [ -f "$ICON_SOURCE" ]; then
  mkdir -p "$RESOURCES/META-INF" "$RESOURCES/icons"

  # Convert source to proper PNG first (source may be JPEG with .png extension)
  TMP_PNG=$(mktemp /tmp/Blamely_icon_XXXX.png)
  sips -s format png "$ICON_SOURCE" --out "$TMP_PNG" >/dev/null 2>&1

  if [ ! -f "$RESOURCES/META-INF/pluginIcon.png" ]; then
    echo "Generating pluginIcon.png (40x40)..."
    sips -z 40 40 "$TMP_PNG" --out "$RESOURCES/META-INF/pluginIcon.png" >/dev/null 2>&1
  fi
  if [ ! -f "$RESOURCES/icons/Blamely13.png" ]; then
    echo "Generating Blamely13.png (13x13)..."
    sips -z 13 13 "$TMP_PNG" --out "$RESOURCES/icons/Blamely13.png" >/dev/null 2>&1
  fi
  if [ ! -f "$RESOURCES/icons/Blamely16.png" ]; then
    echo "Generating Blamely16.png (16x16)..."
    sips -z 16 16 "$TMP_PNG" --out "$RESOURCES/icons/Blamely16.png" >/dev/null 2>&1
  fi
  rm -f "$TMP_PNG"
fi

# Verify required files exist
for f in "$RESOURCES/META-INF/plugin.xml" "$RESOURCES/META-INF/pluginIcon.png" "$RESOURCES/icons/Blamely13.png" "$RESOURCES/icons/Blamely16.png"; do
  if [ ! -f "$f" ]; then
    echo "ERROR: Missing required file: $f"
    exit 1
  fi
done

echo "Building Blamely IntelliJ plugin (creates distributable ZIP)..."
./gradlew clean buildPlugin --no-daemon "$@"

DIST_DIR="$SCRIPT_DIR/build/distributions"
echo ""
if [ -d "$DIST_DIR" ]; then
  ZIP=$(find "$DIST_DIR" -maxdepth 1 -name "*.zip" | head -1)
  if [ -n "$ZIP" ]; then
    echo "Build complete. Plugin distribution:"
    ls -la "$ZIP"
    echo ""
    echo "Contents:"
    unzip -l "$ZIP" | grep -E "plugin\.xml|pluginIcon|Blamely1[36]|\.png$" || true
    echo ""
    echo "Full path: $ZIP"
    echo "Install: Settings -> Plugins -> gear icon -> Install Plugin from Disk -> select the .zip above"
  else
    echo "Build finished but no .zip found in $DIST_DIR"
    exit 1
  fi
else
  echo "Build finished but $DIST_DIR was not created."
  exit 1
fi
