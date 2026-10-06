package ai.blamely.authorship

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// alignLines must reproduce the whole-file LCS DP exactly (mirrors the Go
// TestAlignLinesMatchesFullDP and the TS AlignLines test).
class AlignLinesTest {
    private fun alignLinesFullDP(oldLines: List<String>, newLines: List<String>): IntArray {
        val n = oldLines.size
        val m = newLines.size
        val matched = IntArray(m) { -1 }
        if (n == 0 || m == 0) return matched
        val oldN = oldLines.map { it.filterNot { c -> c.isWhitespace() } }
        val newN = newLines.map { it.filterNot { c -> c.isWhitespace() } }
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = when {
                    oldN[i] == newN[j] -> dp[i + 1][j + 1] + 1
                    dp[i + 1][j] >= dp[i][j + 1] -> dp[i + 1][j]
                    else -> dp[i][j + 1]
                }
            }
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                oldN[i] == newN[j] -> { matched[j] = i; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return matched
    }

    @Test
    fun matchesWholeFileDpOnRandomInputs() {
        val rng = java.util.Random(1)
        val alphabet = listOf("a", "b", " a", "c", "")
        fun gen(k: Int) = List(k) { alphabet[rng.nextInt(alphabet.size)] }
        repeat(50000) {
            val oldLines = gen(rng.nextInt(9))
            val newLines = if (rng.nextInt(3) == 0) {
                gen(rng.nextInt(9))
            } else {
                val cut1 = rng.nextInt(oldLines.size + 1)
                val cut2 = cut1 + rng.nextInt(oldLines.size - cut1 + 1)
                oldLines.subList(0, cut1) + gen(rng.nextInt(4)) + oldLines.subList(cut2, oldLines.size)
            }
            assertArrayEquals(alignLinesFullDP(oldLines, newLines), alignLines(oldLines, newLines),
                "old=$oldLines new=$newLines")
        }
    }

    @Test
    fun oneLineEditInLargeFileIsCheap() {
        val n = 50000
        val oldLines = List(n) { "line ${it % 97}" }
        val newLines = oldLines.toMutableList().also { it[n / 2] = "changed" }
        val start = System.currentTimeMillis()
        val matched = alignLines(oldLines, newLines)
        assertTrue(System.currentTimeMillis() - start < 2000, "too slow")
        assertEquals(-1, matched[n / 2])
        assertEquals(0, matched[0])
        assertEquals(n - 1, matched[n - 1])
    }
}
