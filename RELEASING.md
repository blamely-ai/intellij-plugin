# Releasing Blamely IntelliJ plugin

## Version

Keep **`src/main/resources/META-INF/plugin.xml`** `<version>` in sync with the Git tag (without leading `v`).

## Local release build (obfuscated ZIP)

```bash
chmod +x release-build.sh
./release-build.sh
```

Equivalent Gradle invocation:

```bash
./gradlew clean buildPlugin -Pblamely.release=true -Pblamely.obfuscate=true
```

Artifact: **`build/distributions/blamely-<version>.zip`** (exact filename follows Gradle `buildPlugin` output).

- **`-Pblamely.release=true`** — Kotlin strips some assertions for smaller bytecode.
- **`-Pblamely.obfuscate=true`** — ProGuard renames non-kept symbols in the composed plugin JAR; entrypoints listed in **`proguard/blamely-release.pro`** stay stable (plugin.xml, services, actions, Gson models).

Day-to-day dev/sandbox builds usually skip obfuscation (faster): `./gradlew buildPlugin` or `./build.sh`.

## GitHub Release

Workflow **`.github/workflows/release.yml`** runs on tags **`v*`** and uploads the ZIP from the same obfuscated **`buildPlugin`** command.

1. Commit any version/changelog updates on `main`.
2. Create an annotated tag matching `plugin.xml` (example for version **1.1.0**):

   ```bash
   git tag -a v1.1.0 -m "Release v1.1.0"
   git push origin v1.1.0
   ```

3. The **Release** workflow builds with obfuscation and publishes the asset.

## JetBrains Marketplace

Upload the same **`build/distributions/*.zip`**. Optional: configure **`signPlugin`** with Marketplace credentials when you add signing to CI.
