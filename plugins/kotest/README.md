# Kotest plugin

Apply `//plugins/kotest/kotest.module-template.yaml` after installation with `ktc-plugins`.
It configures test dependencies, KSP discovery, JVM's JUnit runner and the `kotest` plugin.

Use `./kotlin test --platform <jvm-or-native-target>` for JVM/Native, and
`./kotlin check kotestJs kotestWasm` for JS/Wasm-JS in Node 24+ and Chromium. Web targets
absent from a module are skipped. Optional settings are `browser`, `nodeExecutable`, and
`timeoutSeconds`. Install Chromium with `npx --yes playwright@1.61.0 install chromium`.

See https://github.com/Heapy/ktc-kotest for configuration, pinned versions, limits and CI.
