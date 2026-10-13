package io.heapy.ktc.plugins.kotest

import org.jetbrains.amper.plugins.Configurable
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

@Configurable
interface KotestSettings {
    val browser: Boolean get() = true
    val nodeExecutable: String get() = "node"
    val timeoutSeconds: Int get() = 900
}

internal data class TestResult(
    val name: String,
    val duration: String = "0",
    val failure: String? = null,
    val skipped: Boolean = false,
)

// TeamCity failure details can contain very long stack traces. A repeated regex
// group uses the JVM call stack for each character and can overflow on real failures.
internal fun serviceMessageAttributes(line: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    var index = line.indexOf(' ')
    if (index < 0) return result
    while (index < line.length) {
        while (index < line.length && line[index].isWhitespace()) index++
        if (index == line.length || line[index] == ']') break
        val equals = line.indexOf('=', index)
        check(equals > index && equals + 1 < line.length && line[equals + 1] == '\'') {
            "Malformed Kotest service message attribute"
        }
        val key = line.substring(index, equals)
        index = equals + 2
        val valueStart = index
        while (index < line.length && line[index] != '\'') {
            if (line[index] == '|') {
                check(index + 1 < line.length) { "Truncated Kotest service message escape" }
                index += 2
            } else {
                index++
            }
        }
        check(index < line.length) { "Unterminated Kotest service message attribute" }
        result[key] = unescape(line.substring(valueStart, index))
        index++
    }
    return result
}

internal fun unescape(value: String): String =
    buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char == '|' && index < value.length) {
                append(
                    when (val escaped = value[index++]) {
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> escaped
                    },
                )
            } else {
                append(char)
            }
        }
    }

internal fun parseResults(lines: List<String>): List<TestResult> {
    val running = linkedMapOf<String, TestResult>()
    val completed = mutableListOf<TestResult>()
    var openSuites = 0
    for (line in lines) {
        if (!line.startsWith("##teamcity[")) continue
        val event = line.substringAfter('[').substringBefore(' ').substringBefore(']')
        val attrs = serviceMessageAttributes(line)
        val name = attrs["name"] ?: ""
        when (event) {
            "testSuiteStarted" -> {
                openSuites++
            }

            "testSuiteFinished" -> {
                openSuites--
                check(openSuites >= 0) { "Unbalanced Kotest suite events" }
            }

            "testStarted" -> {
                check(name !in running) { "Duplicate running test: $name" }
                running[name] = TestResult(name)
            }

            "testFailed" -> {
                running[name] = (running[name] ?: TestResult(name)).copy(failure = listOfNotNull(attrs["message"], attrs["details"]).joinToString("\n"))
            }

            "testIgnored" -> {
                completed += (running.remove(name) ?: TestResult(name)).copy(skipped = true)
            }

            "testFinished" -> {
                val result = running.remove(name)
                check(result != null) { "Test finished without starting: $name" }
                completed += result.copy(duration = attrs["duration"] ?: "0")
            }
        }
    }
    check(running.isEmpty() && openSuites == 0) { "Incomplete Kotest run: ${running.keys}, open suites: $openSuites" }
    check(completed.any { !it.skipped }) { "Kotest ran no tests; check KSP configuration and the generated entry point" }
    return completed
}

private fun xml(value: String): String =
    value
        .filter { it >= ' ' || it == '\n' || it == '\r' || it == '\t' }
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

internal fun junitReport(
    name: String,
    results: List<TestResult>,
): String =
    buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<testsuite name=\"${xml(name)}\" tests=\"${results.size}\" failures=\"${results.count { it.failure != null }}\" skipped=\"${results.count { it.skipped }}\">")
        results.forEach { result ->
            val seconds = (result.duration.toDoubleOrNull() ?: 0.0) / 1000
            appendLine("  <testcase classname=\"${xml(name)}\" name=\"${xml(result.name)}\" time=\"$seconds\">")
            result.failure?.let { appendLine("    <failure message=\"${xml(it)}\">${xml(it)}</failure>") }
            if (result.skipped) appendLine("    <skipped />")
            appendLine("  </testcase>")
        }
        appendLine("</testsuite>")
    }

private fun runProcess(
    command: List<String>,
    directory: Path,
    log: Path,
    timeoutSeconds: Int,
): Int {
    val process =
        ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
    try {
        check(process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)) { "Process timed out; see $log" }
        return process.exitValue()
    } finally {
        if (process.isAlive) {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            process.waitFor()
        }
    }
}

internal fun declaresWebTarget(effectiveSettings: String, platform: String): Boolean {
    val groups = Regex("^settings@([A-Za-z0-9+]+):\\s*$", RegexOption.MULTILINE)
        .findAll(effectiveSettings).map { it.groupValues[1] }.toList()
    check(groups.isNotEmpty()) { "Cannot discover targets from KTC 0.13 show settings output" }
    return groups.any { platform in it.split('+') }
}

@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun runWebTests(
    @Input(inferTaskDependency = false) projectDir: Path,
    moduleName: String,
    settings: KotestSettings,
    platform: String,
    @Output outputDir: Path,
) {
    require(platform == "js" || platform == "wasmJs")
    require(settings.timeoutSeconds > 0)
    outputDir.createDirectories()
    val buildDir = outputDir.resolve("compile")
    val task = if (platform == "js") "linkJsTest" else "linkWasmJsTestDebug"
    val wrapper = if (System.getProperty("os.name").startsWith("Windows")) listOf("cmd", "/c", projectDir.resolve("kotlin.bat").toString()) else listOf(projectDir.resolve("kotlin").toString())
    // Invalidate previous reports before any failing or skipped run.
    outputDir.resolve("TEST-$moduleName-$platform.xml").deleteIfExists()
    outputDir.resolve("TEST-$moduleName-$platform-browser.xml").deleteIfExists()
    val modelLog = outputDir.resolve("settings.log")
    val modelStatus = runProcess(wrapper + listOf("show", "settings", "-m", moduleName, "--build-dir", buildDir.toString()), projectDir, modelLog, settings.timeoutSeconds)
    check(modelStatus == 0) { "Cannot read KTC module settings; see $modelLog" }
    if (!declaresWebTarget(modelLog.readText(), platform)) {
        val message = "Kotest $platform skipped: target not declared in $moduleName"
        outputDir.resolve("test.log").writeText(message + "\n")
        println(message)
        return
    }
    val buildLog = outputDir.resolve("compile.log")
    val status = runProcess(wrapper + listOf("task", ":$moduleName:$task", "--build-dir", buildDir.toString()), projectDir, buildLog, settings.timeoutSeconds)
    check(status == 0) { "KTC test linking failed:\n" + buildLog.readLines().takeLast(50).joinToString("\n") }
    // KTC 0.13's JS linker produces per-file ES modules. Wasm emits a single
    // module with its .wasm and import-object siblings in the same directory.
    val variant = if (platform == "js") "release" else "debug"
    val entry = buildDir.resolve("artifacts/CompiledWebArtifact/${moduleName}${platform}Test$variant/kotlin-output/${moduleName}_test.mjs")
    check(entry.isRegularFile()) { "KTC 0.13 test entry point is missing: $entry" }
    val log = outputDir.resolve("test.log")
    val exitCode = runProcess(listOf(settings.nodeExecutable, "--unhandled-rejections=strict", entry.toString()), projectDir, log, settings.timeoutSeconds)
    val results = parseResults(log.readLines())
    outputDir.resolve("TEST-$moduleName-$platform.xml").writeText(junitReport("$moduleName.$platform", results))
    val failed = results.filter { it.failure != null }
    check(exitCode == 0 && failed.isEmpty()) { "Kotest $platform failed (exit $exitCode):\n" + failed.joinToString("\n") { "${it.name}: ${it.failure}" } + "\nLog: $log" }
    if (settings.browser) {
        val browserResults = runBrowser(entry, outputDir.resolve("browser.log"), results, settings.timeoutSeconds)
        outputDir.resolve("TEST-$moduleName-$platform-browser.xml").writeText(junitReport("$moduleName.$platform.browser", browserResults))
        check(browserResults.none { it.failure != null }) { "Browser test failures; see ${outputDir.resolve("browser.log")}" }
        println("Kotest $platform Chromium: ${browserResults.size} tests")
    }
    println("Kotest $platform: ${results.size} tests, ${results.count { it.skipped }} skipped; JUnit report in $outputDir")
}
