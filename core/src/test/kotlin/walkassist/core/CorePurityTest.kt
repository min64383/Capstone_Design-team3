package walkassist.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** core에 Android·ARCore 의존이 들어오지 않았는지 소스 수준에서 한 번 더 확인한다 (§2.2-8). 1차 차단은 모듈 의존성이다. */
class CorePurityTest {

    private val forbidden = Regex("""^\s*import\s+(android\.|androidx\.|com\.google\.ar\.)""")

    @Test
    fun `core sources do not import android or arcore`() {
        val root = File(System.getProperty("walkassist.coreSrc") ?: error("run via Gradle"))
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "no sources found under $root")
        val violations = files.flatMap { f ->
            f.readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}: ${it.value.trim()}" }
        }
        assertTrue(violations.isEmpty(), "forbidden imports:\n" + violations.joinToString("\n"))
    }
}
