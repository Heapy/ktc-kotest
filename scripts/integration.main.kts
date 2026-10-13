#!/usr/bin/env kotlinr
// Run with Kotlin 2.4.21+ and JDK 25: kotlinr scripts/integration.main.kts

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

val repo = __FILE__.canonicalFile.parentFile.parentFile
val windows = System.getProperty("os.name").startsWith("Windows")

fun temporary(prefix: String, block: (File) -> Unit) {
    val directory = Files.createTempDirectory(repo.resolve("build").apply { mkdirs() }.toPath(), prefix).toFile()
    block(directory)
    directory.deleteRecursively()
}

fun write(root: File, path: String, text: String) {
    root.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
}

fun copy(source: File, destination: File) {
    check(source.copyRecursively(destination, overwrite = true)) { "Could not copy $source to $destination" }
    if (!windows) {
        source.walkTopDown().filter { it.isFile && it.canExecute() }.forEach { file ->
            val target = if (source.isDirectory) destination.resolve(file.relativeTo(source)) else destination
            check(target.setExecutable(true, false)) { "Could not preserve executable permission: $target" }
        }
    }
}

data class CommandResult(val exitCode: Int, val output: String)
fun command(directory: File, arguments: List<String>, environment: Map<String, String?> = emptyMap(), timeout: Long = 600): CommandResult {
    val log = Files.createTempFile("ktc-command-", ".log").toFile()
    try {
        val process = ProcessBuilder(arguments).directory(directory).redirectErrorStream(true).redirectOutput(log).apply {
            environment.forEach { (key, value) -> if (value == null) environment().remove(key) else environment()[key] = value }
        }.start()
        try {
            check(process.waitFor(timeout, TimeUnit.SECONDS)) { "Timed out: $arguments\n${log.readText()}" }
            return CommandResult(process.exitValue(), log.readText())
        } finally {
            if (process.isAlive) {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly().waitFor()
            }
        }
    } finally { log.delete() }
}

fun toolchain(project: File, vararg arguments: String, succeeds: Boolean = true, diagnostic: String? = null): String {
    val wrapper = project.resolve(if (windows) "kotlin.bat" else "kotlin").absolutePath
    val invocation = if (windows) listOf("cmd.exe", "/c", wrapper) else listOf("sh", wrapper)
    val result = command(project, invocation + arguments)
    check((result.exitCode == 0) == succeeds) { "Unexpected exit ${result.exitCode}: ${arguments.toList()}\n${result.output}" }
    check(diagnostic == null || diagnostic in result.output) { "Missing diagnostic $diagnostic:\n${result.output}" }
    println("PASS: ${arguments.joinToString(" ")} (${if (succeeds) "success" else "expected failure"})")
    return result.output
}

temporary("kotest-integration-") { project ->
    for (name in listOf("kotlin", "kotlin.bat", "project.yaml", "plugins/kotest", "example")) {
        copy(repo.resolve(name), project.resolve(name))
    }
    toolchain(project, "check", "kotestJs", "kotestWasm", "-m", "example", diagnostic = "Chromium: 2 tests")
    for (platform in listOf("js", "wasmJs")) {
        val task = if (platform == "js") "kotestJs" else "kotestWasm"
        val output = project.resolve("build/tasks/_example_${task}@kotest")
        for (suffix in listOf("", "-browser")) {
            val report = output.resolve("TEST-example-$platform$suffix.xml").readText()
            check("tests=\"2\"" in report && "failures=\"0\"" in report)
        }
    }
    val source = project.resolve("example/test/GreetingTest.kt")
    val original = source.readText()
    source.writeText(original.replace("shouldBe \"Hello, Kotlin!\"", "shouldBe \"wrong\""))
    for (task in listOf("kotestJs", "kotestWasm")) {
        toolchain(project, "check", task, "-m", "example", succeeds = false, diagnostic = "failed (exit")
        val platform = if (task == "kotestJs") "js" else "wasmJs"
        val output = project.resolve("build/tasks/_example_${task}@kotest")
        check("failures=\"1\"" in output.resolve("TEST-example-$platform.xml").readText())
        check(!output.resolve("TEST-example-$platform-browser.xml").exists()) { "Stale browser report survived failure" }
    }
    source.writeText("""
        package example
        import io.kotest.core.spec.style.FunSpec
        class GreetingTest : FunSpec({})
    """.trimIndent() + "\n")
    toolchain(project, "check", "kotestJs", "-m", "example", succeeds = false, diagnostic = "Kotest ran no tests")
    source.writeText(original)
    val module = project.resolve("example/module.yaml")
    module.writeText("""
        product: jvm/lib
        apply: [//plugins/kotest/kotest.module-template.yaml]
    """.trimIndent() + "\n")
    toolchain(project, "check", "kotestJs", "kotestWasm", "-m", "example", diagnostic = "skipped: target not declared")
    val nativeTarget = when {
        windows -> "mingwX64"
        System.getProperty("os.name").startsWith("Mac") -> "macosArm64"
        else -> "linuxX64"
    }
    module.writeText("""
        product:
          type: kmp/lib
          platforms: [$nativeTarget]
        apply: [//plugins/kotest/kotest.module-template.yaml]
    """.trimIndent() + "\n")
    toolchain(project, "check", "kotestJs", "kotestWasm", "-m", "example", diagnostic = "skipped: target not declared")
    check(project.resolve("build/tasks").walkTopDown().none { it.isFile && it.name.startsWith("TEST-example-") && it.extension == "xml" }) {
        "Skipped targets must not leave reports from previous runs"
    }
}
println("Kotest integration passed: Node/Chromium, JS/Wasm, failures, empty runs, absent platforms and stale reports.")
