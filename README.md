# ktc-kotest

Kotest integration for **Kotlin Toolchain 0.13.0**. One module template configures Kotest
for JVM, Native, JS and Wasm-JS. JVM and Native use the toolchain's test runners; JS and
Wasm-JS run in Node.js and Chromium with JUnit XML reports.

## Install

From your consumer project:

```sh
./ktc-plugins add Heapy/ktc-kotest --branch main
```

The installer registers `plugins/kotest` and pins the resolved commit in its lockfile.
Apply the installed template to each module that uses Kotest:

```yaml
apply:
  - //plugins/kotest/kotest.module-template.yaml
```

The template enables the `kotest` plugin and adds test-only Kotest engine/assertions,
the JVM JUnit runner, and KSP discovery. It ships inside the installed module; no separate
template download is needed. You do not need to repeat the dependencies or plugin activation.
For immutable installation, use `--commit <full-SHA>` instead of `--branch main`.

Node.js **24+** must be on PATH. Install the matching Chromium runtime once:

```sh
npx --yes playwright@1.61.0 install chromium
# Linux hosts may also need system dependencies:
npx --yes playwright@1.61.0 install --with-deps chromium
```

The plugin does not download browsers automatically.

## Run

```sh
./kotlin test --platform jvm
./kotlin test --platform macosArm64 # select a runnable Native target for your host
./kotlin check kotestJs kotestWasm
```

Use `-m <module>` to select a consumer. JS/Wasm checks are automatically skipped for
modules without the corresponding target, so the same template works for JVM-only and
Native-only libraries. `./kotlin check` also invokes ordinary toolchain tests; select native
test platforms explicitly when your project contains device targets that cannot run locally.

Optional settings:

```yaml
plugins:
  kotest:
    browser: true
    nodeExecutable: node
    timeoutSeconds: 900
```

Each web check runs Node and Chromium by default and requires matching test identities.
`browser: false` selects an intentional Node-only run. The timeout applies to each subprocess
and browser run. A failed test fails the check even if Node exits with code zero. Empty or
incomplete runs fail. Raw logs and `TEST-*.xml` reports are stored in
`build/tasks/_<module>_<kotestJs|kotestWasm>@kotest/`; browser reports have a `-browser` suffix.
Reports from a previous invocation are removed before a new failed or skipped run.

## Versions and boundaries

- Kotlin Toolchain **0.13.0**, tested with Kotlin **2.4.20** and JDK **25**.
- Kotest **6.2.5**, KSP **2.3.12**, Playwright **1.61.0**.
- Test-runtime overrides for coroutines **1.11.0** and kotlinx-io **0.9.1**, verified with
  the Native/Wasm tests. The template keeps these and the Kotest processor/runtime aligned.
- Kotlin's compiler version remains a consumer setting; the plugin does not override it.
- KTC 0.13 has no JS test task. Its built-in Wasm browser runner attaches completion tracking
  after importing the bundle; Kotest can start before that tracking is active. This adapter
  attaches listeners before dynamic import.
- The adapter invokes main-project link tasks in isolated build directories. Task names,
  artifact paths and the effective-settings output used for target discovery are pinned to
  KTC 0.13 and must be revalidated when upgrading.
- JVM/Native execution uses the standard KTC test command. Android variants and Wasm-WASI
  are not covered by this adapter. External npm imports require separate installation and
  resolution; the included example and the kotaml trial require none.

## Verify and maintain

```sh
./kotlin test --platform jvm --platform macosArm64
kotlinr scripts/integration.main.kts
```

The integration script needs JDK 25, a Kotlin script runner, Node 24+ and installed Chromium.
It verifies passing JS/Wasm tests in both runtimes, intentional test failures, empty runs,
JVM/Native-only skipping and stale report removal. Failed integration fixtures are retained
under `build/` for diagnosis. CI runs JVM, a host Native target and integration tests on Linux,
macOS and Windows. All action references are pinned to commits.

The root `ktc-plugin.yaml` exports selector `kotest`. The module contains its own template
and has no repository-local module/catalog dependencies. Preserve the root Apache-2.0
license when copying manually.

Extracted from the Kotlin Toolchain migration of [kotaml](https://github.com/Heapy/kotaml),
where both libraries' JS/Wasm suites ran in Node and Chromium. This repository supplies
build integration; [Kotest](https://github.com/kotest/kotest) is the upstream test framework.

Licensed under [Apache-2.0](LICENSE).
