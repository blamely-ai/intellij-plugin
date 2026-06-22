package ai.blamely.authorship

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.InputStreamReader

// Runs the SHARED golden vectors (src/test/resources/golden_vectors.json, synced
// from blamely-cli's canonical copy) through the Kotlin engine. The Go and TS
// ports run the same file; drift in any one fails its run. docs/attribution-v2-design.md §6.
class AttributionGoldenTest {

    private data class GoldenFile(val version: String, val cases: List<GoldenCase>)
    private data class GoldenCase(
        val name: String,
        val prior: List<PriorRange>?,
        val baseline: String,
        @SerializedName("new") val newContent: String,
        val author: AuthorSpec,
        val expect: List<String>,
        @SerializedName("expect_overrode") val expectOverrode: List<String?>? = null,
    )
    private data class PriorRange(val start: Int, val end: Int, val author: String)
    private data class AuthorSpec(val author: String, val tool: String?, @SerializedName("gen_type") val genType: String?)

    private fun typesByLine(wl: WorkingLog, n: Int): List<String> {
        val out = MutableList(n) { "" }
        for (r in wl.lines) {
            var ln = r.start
            while (ln <= r.end && ln <= n) {
                out[ln - 1] = r.author.type.wire
                ln++
            }
        }
        return out
    }

    private fun overrodeTypesByLine(wl: WorkingLog, n: Int): List<String?> {
        val out = MutableList<String?>(n) { null }
        for (r in wl.lines) {
            val ov = r.overrode ?: continue
            var ln = r.start
            while (ln <= r.end && ln <= n) {
                out[ln - 1] = ov.type.wire
                ln++
            }
        }
        return out
    }

    @TestFactory
    fun goldenVectors(): List<DynamicTest> {
        val stream = javaClass.getResourceAsStream("/golden_vectors.json")
            ?: error("golden_vectors.json not found on test classpath")
        val gf = Gson().fromJson(InputStreamReader(stream), GoldenFile::class.java)
        check(gf.cases.isNotEmpty()) { "no golden cases" }

        return gf.cases.map { c ->
            DynamicTest.dynamicTest(c.name) {
                val prior = c.prior?.let { ranges ->
                    WorkingLog(lines = ranges.map {
                        LineAttribution(it.start, it.end, Author(AuthorType.fromWire(it.author)))
                    })
                }
                val author = Author(
                    type = AuthorType.fromWire(c.author.author),
                    tool = c.author.tool ?: "",
                    genType = c.author.genType ?: "",
                )
                val wl = attribute(prior, c.baseline, c.newContent, author)
                val got = typesByLine(wl, c.expect.size)
                assertEquals(c.expect, got, "case ${c.name}")
                c.expectOverrode?.let { want ->
                    assertEquals(want, overrodeTypesByLine(wl, want.size), "case ${c.name} overrode")
                }
            }
        }
    }
}
