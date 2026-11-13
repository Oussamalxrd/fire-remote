package app.fireremote.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every language has every string, with the same placeholders, and every string the code uses exists. */
class StringsTest {
    private val res = File("src/main/res")
    private val entry = Regex("<string name=\"([a-z_]+)\">(.*?)</string>")
    private val arg = Regex("%\\d\\$[sd]")

    private fun load(dir: String) = entry.findAll(File(res, "$dir/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }

    @Test fun translationsMatchEnglish() {
        val en = load("values")
        assertTrue(en.size > 100)
        for (lang in listOf("fr", "ar", "de", "es")) {
            val t = load("values-$lang")
            assertEquals("keys in $lang", en.keys, t.keys)
            for ((k, v) in en) assertEquals("placeholders of $k in $lang", arg.findAll(v).map { it.value }.sorted().toList(), arg.findAll(t[k]!!).map { it.value }.sorted().toList())
        }
    }

    @Test fun codeUsesOnlyExistingStrings() {
        val en = load("values").keys
        val used = File("src/main/java").walk().filter { it.extension == "kt" }
            .flatMap { Regex("R\\.string\\.([a-z_]+)").findAll(it.readText()).map { m -> m.groupValues[1] } }.toSet()
        assertTrue(used.size > 50)
        assertEquals(emptySet<String>(), used - en)
    }
}
