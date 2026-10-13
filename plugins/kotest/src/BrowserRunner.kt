package io.heapy.ktc.plugins.kotest

import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

// Register the console listener before importing the test module. A static import
// starts Kotest before KTC 0.13's browser loader installs its completion tracking.
internal fun runBrowser(entry: Path, log: Path, expected: List<TestResult>, timeoutSeconds: Int): List<TestResult> {
    val root = entry.parent.toRealPath()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val messages = mutableListOf<String>()
    val pageErrors = mutableListOf<String>()
    server.createContext("/") { request ->
        request.use { exchange ->
            val relative = exchange.requestURI.path.removePrefix("/")
            val file = root.resolve(relative).normalize()
            if (relative == "") {
                val html = "<!doctype html><meta charset='utf-8'><title>Kotest</title>".toByteArray()
                exchange.responseHeaders.set("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, html.size.toLong())
                exchange.responseBody.write(html)
            } else if (file.startsWith(root) && file.isRegularFile() && file.toRealPath().startsWith(root)) {
                val type = if (relative.endsWith(".wasm")) "application/wasm" else "text/javascript"
                exchange.responseHeaders.set("Content-Type", type)
                exchange.sendResponseHeaders(200, Files.size(file))
                Files.newInputStream(file).use { it.copyTo(exchange.responseBody) }
            } else {
                exchange.sendResponseHeaders(404, -1)
            }
        }
    }
    server.start()
    try {
        // Task classloaders are isolated, but the JVM ZIP filesystem cache is shared.
        // Serialize driver extraction across those classloaders to avoid one task
        // closing the driver's JAR filesystem while another is copying from it.
        val playwrightInstance = synchronized("io.heapy.ktc.plugins.kotest.driver".intern()) {
            Playwright.create(Playwright.CreateOptions().setEnv(mapOf("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1")))
        }
        playwrightInstance.use { playwright ->
            playwright.chromium().launch().use { browser ->
                browser.newPage().use { page ->
                    page.onConsoleMessage { messages.add(it.text()) }
                    page.onPageError { pageErrors.add(it) }
                    page.addInitScript("""
                        globalThis.__kotest = { open: 0, done: 0, last: performance.now(), error: null };
                        const log = console.log.bind(console);
                        console.log = (...args) => {
                          const line = args.map(String).join(' ');
                          const state = globalThis.__kotest;
                          if (line.startsWith('##teamcity[testSuiteStarted ')) state.open++;
                          if (line.startsWith('##teamcity[testSuiteFinished ')) state.open--;
                          if (line.startsWith('##teamcity[testFinished ') || line.startsWith('##teamcity[testIgnored ')) state.done++;
                          state.last = performance.now();
                          log(...args);
                        };
                        addEventListener('error', e => { globalThis.__kotest.error = String(e.message); });
                        addEventListener('unhandledrejection', e => { globalThis.__kotest.error = String(e.reason); });
                    """.trimIndent())
                    page.navigate("http://127.0.0.1:${server.address.port}/")
                    page.evaluate("name => { import('/' + encodeURIComponent(name)).catch(e => { globalThis.__kotest.error = String(e); }); }", entry.fileName.toString())
                    page.waitForFunction(
                        "count => { const s = globalThis.__kotest; return s.error || (s.done >= count && s.open === 0 && performance.now() - s.last > 250); }",
                        expected.size,
                        Page.WaitForFunctionOptions().setTimeout(timeoutSeconds * 1000.0),
                    )
                    val error = page.evaluate("globalThis.__kotest.error")
                    check(error == null && pageErrors.isEmpty()) { "Browser error: $error ${pageErrors.joinToString()}" }
                }
            }
        }
    } finally {
        server.stop(0)
        log.writeText((messages + pageErrors).joinToString("\n") + "\n")
    }
    val actual = parseResults(messages)
    check(actual.map { it.name }.sorted() == expected.map { it.name }.sorted()) {
        "Browser and Node ran different test sets; see $log"
    }
    return actual
}
