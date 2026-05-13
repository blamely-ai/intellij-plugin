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
}
