package example

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class GreetingTest : FunSpec({
    test("greets a name") {
        greet("Kotlin") shouldBe "Hello, Kotlin!"
    }
    test("preserves Unicode") {
        greet("мир") shouldBe "Hello, мир!"
    }
})
