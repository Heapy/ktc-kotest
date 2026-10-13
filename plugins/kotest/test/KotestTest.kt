package io.heapy.ktc.plugins.kotest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KotestTest {
    @Test
    fun discoversWebTargetsAndSkipsJvmOrNativeOnlyModules() {
        assertTrue(declaresWebTarget("settings@js+wasmJs:\n  kotlin: {}\n", "js"))
        assertTrue(declaresWebTarget("settings@wasmJs:\n", "wasmJs"))
        assertEquals(false, declaresWebTarget("settings@jvm:\n", "js"))
        assertEquals(false, declaresWebTarget("settings@macosArm64:\n", "wasmJs"))
        assertFailsWith<IllegalStateException> { declaresWebTarget("unrecognized output", "js") }
    }

    @Test
    fun recordsFailedTestsEvenWhenNodeExitsSuccessfully() {
        val results =
            parseResults(
                listOf(
                    "##teamcity[testSuiteStarted name='Spec']",
                    "##teamcity[testStarted name='Spec.should |\'work|\'']",
                    "##teamcity[testFailed name='Spec.should |\'work|\'' message='expected &lt;value&gt;' details='first|nsecond||third']",
                    "##teamcity[testFinished name='Spec.should |\'work|\'' duration='123']",
                    "##teamcity[testSuiteFinished name='Spec']",
                ),
            )
        assertEquals(1, results.size)
        assertEquals("Spec.should 'work'", results.single().name)
        assertEquals("expected &lt;value&gt;\nfirst\nsecond|third", results.single().failure)
        val xml = junitReport("fixture", results)
        assertTrue(xml.contains("failures=\"1\""))
        assertTrue(xml.contains("time=\"0.123\""))
        assertTrue(xml.contains("&amp;lt;value&amp;gt;"))
    }

    @Test
    fun handlesLongFailureStackTracesWithoutRegexStackOverflow() {
        val details = "at example.Test(file.js:123:4)|n".repeat(5000)
        val results = parseResults(listOf(
            "##teamcity[testStarted name='fails']",
            "##teamcity[testFailed name='fails' message='assertion' details='$details']",
            "##teamcity[testFinished name='fails' duration='1']",
        ))
        assertTrue(results.single().failure!!.contains("at example.Test"))
        assertTrue(junitReport("fixture", results).contains("failures=\"1\""))
        assertFailsWith<IllegalStateException> { serviceMessageAttributes("##teamcity[testFailed name='unfinished") }
    }

    @Test
    fun rejectsNoTestsAndIncompleteRun() {
        assertFailsWith<IllegalStateException> { parseResults(listOf("hello")) }
        assertFailsWith<IllegalStateException> { parseResults(listOf("##teamcity[testStarted name='unfinished']")) }
        assertFailsWith<IllegalStateException> { parseResults(listOf("##teamcity[testFinished name='unknown']")) }
        assertFailsWith<IllegalStateException> { parseResults(listOf("##teamcity[testSuiteFinished name='unknown']")) }
    }

    @Test
    fun preservesSkippedResultsAndEscapedNames() {
        val results =
            parseResults(
                listOf(
                    "##teamcity[testStarted name='pass']",
                    "##teamcity[testFinished name='pass' duration='0']",
                    "##teamcity[testIgnored name='skip|[brackets|]' message='reason']",
                ),
            )
        assertEquals("skip[brackets]", results.last().name)
        assertTrue(results.last().skipped)
        assertTrue(junitReport("fixture", results).contains("skipped=\"1\""))
    }
}
