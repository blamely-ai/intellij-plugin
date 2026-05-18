package ai.blamely.utils

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlatformTest {

    @Test
    fun `normalizePath replaces backslash with forward slash`() {
        assertEquals("src/main/kotlin/Foo.kt", Platform.normalizePath("src\\main\\kotlin\\Foo.kt"))
        assertEquals("a/b/c", Platform.normalizePath("a/b/c"))
    }

    @Test
    fun `encodeFilePath replaces slashes with double underscore`() {
        assertEquals("src__main__Foo.kt", Platform.encodeFilePath("src/main/Foo.kt"))
        assertEquals("src__main__Foo.kt", Platform.encodeFilePath("src\\main\\Foo.kt"))
    }

    @Test
    fun `decodeFilePath restores slashes from double underscore`() {
        assertEquals("src/main/Foo.kt", Platform.decodeFilePath("src__main__Foo.kt"))
    }

    @Test
    fun `encode and decode round-trip`() {
        val path = "src/main/kotlin/com/Blamely/Main.kt"
        assertEquals(path, Platform.decodeFilePath(Platform.encodeFilePath(path)))
    }

    @Test
    fun `blameKeyFromSnapshotSidecarPath maps snapshots branch file`() {
        assertEquals("kerim1.py", Platform.blameKeyFromSnapshotSidecarPath("snapshots/master/kerim1.py.blame.json"))
    }

    @Test
    fun `blameKeyFromSnapshotSidecarPath maps logs commits snapshots layout`() {
        assertEquals(
            "kerim1.py",
            Platform.blameKeyFromSnapshotSidecarPath(
                "logs/commits/deadbeefdeadbeefdeadbeefdeadbeef/snapshots/kerim1.py.blame.json",
            ),
        )
    }

    @Test
    fun `blameKeyFromSnapshotSidecarPath decodes encoded stem`() {
        assertEquals(
            "src/foo/bar.py",
            Platform.blameKeyFromSnapshotSidecarPath("snapshots/main/src__foo__bar.py.blame.json"),
        )
    }

    @Test
    fun `blameKeyFromSnapshotSidecarPath returns null for normal paths`() {
        assertNull(Platform.blameKeyFromSnapshotSidecarPath("src/foo.ts"))
    }

    @Test
    fun `normalizeBlamePersistenceKey strips double blame json and corrupted encoded absolute`() {
        val home =
            "/Users/x/.blamely/repos/blamely-ci-test/snapshots/master/__Users__x__.blamely__repos__blamely-ci-test__snapshots__master__kerim1.py.blame.json.blame.json"
        assertEquals("kerim1.py", Platform.normalizeBlamePersistenceKey(home, null))
    }

    @Test
    fun `normalizeBlamePersistenceKey maps snapshot sidecar absolute path`() {
        val abs = "/Users/x/.blamely/repos/repo/snapshots/master/kerim1.py.blame.json"
        assertEquals("kerim1.py", Platform.normalizeBlamePersistenceKey(abs, null))
    }
}
