Optional plugins copied into the Gradle IDE sandbox on every `prepareSandbox` / `runIde`.

Why: `./run-sandbox.sh` runs Gradle `prepareSandbox`, which syncs the sandbox `plugins/` folder to match
`build.gradle.kts`. Anything you install only via Settings → Plugins in the sandbox is dropped next run.

How:
  • Prefer declaring plugins in build.gradle.kts (`compatiblePlugin("…")`, `plugin("id:version")`),
    or use `./run-sandbox.sh --copilot`.
  • Or unzip Marketplace plugins here as folders (same layout as under a real IDE `plugins/`,
    e.g. `github-copilot-intellij/`). Contents are merged into the sandbox by Gradle Sync.

Do not commit proprietary plugin binaries to git unless your license allows it.
