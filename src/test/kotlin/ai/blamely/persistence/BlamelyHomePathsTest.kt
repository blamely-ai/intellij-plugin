package ai.blamely.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BlamelyHomePathsTest {

    @Test
    fun repoKey_isStableForSamePath() {
        val a = BlamelyHomePaths.repoKey("/tmp/my/repo")
        val b = BlamelyHomePaths.repoKey("/tmp/my/repo")
        assertEquals(a, b)
        assertEquals(16, a.length)
    }

    @Test
    fun safeBranchName_sanitizesSlashes() {
        assertEquals("feature-foo", BlamelyHomePaths.safeBranchName("feature/foo"))
    }
}
