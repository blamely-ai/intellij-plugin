# Releasing Blamely IntelliJ plugin

## Version

Keep **`src/main/resources/META-INF/plugin.xml`** `<version>` in sync with the Git tag (without leading `v`).

## Local release build

```bash
chmod +x release-build.sh
./release-build.sh
```

Equivalent Gradle invocation:

```bash
./gradlew clean buildPlugin -Pblamely.release=true
```

Artifact: **`build/distributions/blamely-<version>.zip`** (exact filename follows Gradle `buildPlugin` output).

- **`-Pblamely.release=true`** — Kotlin strips some assertions for smaller bytecode.
- The plugin ships **unobfuscated by design** — Blamely is MIT-licensed open source.

## GitHub Release

Workflow **`.github/workflows/release.yml`** runs on tags **`v*`** and uploads the ZIP from the same **`buildPlugin`** command.

1. Commit any version/changelog updates on `main`.
2. Create an annotated tag matching `plugin.xml` (example for version **1.1.0**):

   ```bash
   git tag -a v1.1.0 -m "Release v1.1.0"
   git push origin v1.1.0
   ```

3. The **Release** workflow builds the plugin ZIP and publishes the asset.

## JetBrains Marketplace

Upload the same **`build/distributions/*.zip`**. Optional: configure **`signPlugin`** with Marketplace credentials when you add signing to CI.
