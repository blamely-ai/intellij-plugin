package ai.blamely.utils

/**
 * Plugin metadata without [com.intellij.ide.plugins.PluginManagerCore] / [com.intellij.ide.plugins.PluginManager]
 * (Marketplace Plugin Verifier flags those as internal on recent platform builds).
 */
object BlamelyPluginInfo {

    private val versionRegex = Regex("<version>\\s*([^<]+?)\\s*</version>")

    /** Reads `<version>` from this plugin's bundled `META-INF/plugin.xml`. */
    fun readVersion(anchor: Class<*>): String {
        val stream = anchor.classLoader.getResourceAsStream("META-INF/plugin.xml") ?: return "?"
        return stream.bufferedReader().use { input ->
            versionRegex.find(input.readText())?.groupValues?.get(1)?.trim().orEmpty().ifEmpty { "?" }
        }
    }
}
