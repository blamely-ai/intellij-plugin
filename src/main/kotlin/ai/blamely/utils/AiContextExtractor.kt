package ai.blamely.utils

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Component
import java.awt.Container
import java.awt.Window
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.text.JTextComponent

object AiContextExtractor {

    data class AiContext(
        val prompt: String? = null,
        val model: String? = null,
        val provider: String? = null,
        val interactionType: String? = null
    )

    private val AI_TOOL_WINDOW_IDS = listOf(
        "GitHub Copilot", "Copilot", "Copilot Chat", "Copilot Chat Panel",
        "Codeium", "Codeium Chat",
        "Tabnine", "Tabnine Chat",
        "Amazon Q",
        "JetBrains AI Assistant", "AI Assistant",
        "CodeGPT", "Chat", "Cursor", "Cursor Chat"
    )

    private val PACKAGE_TO_PROVIDER = mapOf(
        "com.github.copilot" to "github-copilot",
        "copilot" to "github-copilot",
        "com.codeium" to "codeium",
        "codeium" to "codeium",
        "com.tabnine" to "tabnine",
        "tabnine" to "tabnine",
        "com.supermaven" to "supermaven",
        "supermaven" to "supermaven",
        "com.codegpt" to "codegpt",
        "codegpt" to "codegpt",
        "software.amazon.codewhisperer" to "amazon-q",
        "software.aws.toolkits" to "amazon-q",
        "amazon.q" to "amazon-q",
        "com.jetbrains.ai" to "jetbrains-ai",
        "jetbrains.ai" to "jetbrains-ai",
        "com.cursor" to "cursor",
        "cursor" to "cursor"
    )

    private val KNOWN_MODEL_PATTERNS = listOf(
        "gpt-5", "gpt-4", "gpt-3.5", "gpt5", "gpt4", "gpt3", "gpt-4o", "gpt4o",
        "gpt-5.1", "gpt-5.2", "gpt-5.3", "gpt-5.4", "gpt-4.1",
        "5.1-codex", "5.2-codex", "5.3-codex",
        "claude", "opus", "sonnet", "haiku",
        "gemini", "palm", "3 flash", "3 pro",
        "codellama", "llama", "mixtral", "mistral",
        "deepseek", "starcoder", "codestral",
        "o1-mini", "o1-preview", "o3-mini", "o4-mini",
        "chatgpt", "grok"
    )

    /** Patterns that need word-boundary matching to avoid false positives (e.g. "codex" in "Blamely"). */
    private val WORD_BOUNDARY_PATTERNS = listOf(
        "codex", "mini", "min", "preview", "4o", "o3", "o4", "4.5", "4.6", "2.5"
    )

    /** Strings that are our own plugin and must never be treated as AI model names. */
    private val SELF_REJECT = listOf("Blamely", "codex-vita", "Blamely", "blamely")

    private fun matchesKnownModel(lower: String): Boolean {
        if (SELF_REJECT.any { lower.contains(it) }) return false
        if (KNOWN_MODEL_PATTERNS.any { lower.contains(it) }) return true
        for (p in WORD_BOUNDARY_PATTERNS) {
            val idx = lower.indexOf(p)
            if (idx < 0) continue
            val before = if (idx > 0) lower[idx - 1] else ' '
            val after = if (idx + p.length < lower.length) lower[idx + p.length] else ' '
            val bOk = !before.isLetterOrDigit() || before == '-' || before == '_'
            val aOk = !after.isLetterOrDigit() || after == '-' || after == '_'
            if (bOk && aOk) return true
        }
        return false
    }


    /** Rejects Java package / class names so we never report them as the model. */
    private fun looksLikePackageOrClassName(s: String): Boolean {
        if (s.isBlank()) return true
        val lower = s.lowercase()
        if (lower.contains("com.") || lower.contains("org.") || lower.contains("net.")) return true
        if (lower.contains(".copilot") || lower.contains(".codeium") || lower.contains(".tabnine")) return true
        if (lower.matches(Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+$"))) return true  // package-like
        return false
    }

    fun extract(project: Project, action: Any?, event: AnActionEvent?): AiContext {
        val provider = detectProvider(action)
        val interactionType = detectInteractionType(action, event)

        val promptFromAction = tryExtractFromActionReflection(action)
        val modelFromAction = tryExtractModelReflection(action)

        val promptFromEditor = event?.let { tryExtractFromEditorSelection(it) }
        val promptFromToolWindow = tryExtractFromChatToolWindow(project)

        // Prefer actual user input (chat panel / inline) over action reflection (often placeholder like "QUERY")
        val prompt = listOfNotNull(
            promptFromToolWindow?.takeIf { !isPlaceholderPrompt(it) },
            promptFromAction,
            promptFromEditor?.takeIf { !isPlaceholderPrompt(it) }
        ).firstOrNull()

        val modelFromUI = tryExtractModelFromChatUI(project)
        val modelFromChatReflection = tryExtractModelFromCopilotChatReflection()
        val modelFromClasses = tryExtractModelFromLoadedClasses()
        val modelFromSettings = tryExtractModelFromCopilotSettings()

        val rawModel = modelFromAction ?: modelFromUI ?: modelFromChatReflection ?: modelFromClasses ?: modelFromSettings
        val model = sanitizeModelForReport(rawModel)

        val promptFinal = prompt?.takeIf { !isPlaceholderPrompt(prompt) }?.take(500)

        return AiContext(
            prompt = promptFinal,
            model = model,
            provider = provider,
            interactionType = interactionType
        )
    }

    fun extractFromProject(project: Project): AiContext {
        // Do not scrape UI when write lock is held (e.g. during Copilot apply) — can trigger "modal progress under write action"
        if (ApplicationManager.getApplication().isWriteAccessAllowed()) return AiContext()
        val promptRaw = tryExtractFromChatToolWindow(project)
        val promptFinal = promptRaw?.takeIf { !isPlaceholderPrompt(promptRaw) }?.take(500)
        val modelFromUI = tryExtractModelFromChatUI(project)
        val modelFromChatReflection = tryExtractModelFromCopilotChatReflection()
        val modelFromClasses = tryExtractModelFromLoadedClasses()
        val modelFromSettings = tryExtractModelFromCopilotSettings()
        val modelFromAllWindows = tryExtractModelFromAllWindows()
        val rawModel = modelFromUI ?: modelFromChatReflection ?: modelFromClasses ?: modelFromSettings ?: modelFromAllWindows
        val model = sanitizeModelForReport(rawModel)

        return AiContext(
            prompt = promptFinal,
            model = model
        )
    }

    /** Last resort: scan all AWT windows (including popups) for model-like text. Must run on EDT. */
    private fun tryExtractModelFromAllWindows(): String? {
        return try {
            for (window in Window.getWindows()) {
                if (!window.isShowing) continue
                val model = findModelInUI(window)
                if (model != null) return model
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Tries to get current chat model via reflection on Copilot chat classes (getModel, getSelectedModel, etc.).
     */
    private fun tryExtractModelFromCopilotChatReflection(): String? {
        try {
            val classLoader = AiContextExtractor::class.java.classLoader
            val classNames = listOf(
                "com.github.copilot.chat.CopilotChatService",
                "com.github.copilot.chat.settings.CopilotChatSettings",
                "com.github.copilot.chat.CopilotChatViewModel"
            )
            val methodNames = listOf("getModel", "getSelectedModel", "getCurrentModel", "getModelId", "getChatModel", "getActiveModel")
            for (clsName in classNames) {
                try {
                    val cls = Class.forName(clsName, false, classLoader)
                    val instance = tryGetSingletonInstance(cls) ?: continue
                    for (methodName in methodNames) {
                        try {
                            val method = cls.getMethod(methodName)
                            val value = method.invoke(instance)?.toString()?.trim()
                            if (!value.isNullOrBlank() && !looksLikePackageOrClassName(value) && value.length in 2..80) {
                                val lower = value.lowercase()
                                if (matchesKnownModel(lower)) return cleanModelName(value)
                            }
                        } catch (_: Throwable) {}
                    }
                    val fromScan = scanObjectForModel(instance)
                    if (fromScan != null) return fromScan
                } catch (_: ClassNotFoundException) {
                } catch (_: NoClassDefFoundError) {}
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Maps a raw package/class name to a clean provider name.
     */
    fun resolveProviderName(rawPackageOrClass: String?): String {
        if (rawPackageOrClass == null) return "unknown"
        val lower = rawPackageOrClass.lowercase()
        for ((pkg, name) in PACKAGE_TO_PROVIDER) {
            if (lower.contains(pkg)) return name
        }
        return rawPackageOrClass
    }

    private fun detectProvider(action: Any?): String? {
        if (action == null) return null
        val cls = action.javaClass.name.lowercase()
        for ((pkg, name) in PACKAGE_TO_PROVIDER) {
            if (cls.contains(pkg)) return name
        }
        return null
    }

    private fun detectInteractionType(action: Any?, event: AnActionEvent?): String? {
        if (action == null) return null
        val cls = action.javaClass.name.lowercase()
        val actionId = event?.let {
            com.intellij.openapi.actionSystem.ActionManager.getInstance()
                .getId(action as? com.intellij.openapi.actionSystem.AnAction ?: return@let null)
        }?.lowercase() ?: ""

        return when {
            actionId.contains("inline") && (actionId.contains("chat") || actionId.contains("edit")) -> "chat_inline"
            cls.contains("inline") && cls.contains("chat") -> "chat_inline"
            (actionId.contains("apply") || actionId.contains("insert") || actionId.contains("keep")) &&
                (
                    actionId.contains("chat") || actionId.contains("copilot") ||
                        actionId.contains("claude") || actionId.contains("anthropic") ||
                        cls.contains("chatpanel") || cls.contains("chat.panel") ||
                        cls.contains("copilot.chat") || cls.contains("chat.ui")
                    ) -> "chat_panel"
            actionId.contains("chat") || cls.contains("chatpanel") || cls.contains("chat.panel") ||
                cls.contains("copilot.chat") -> "chat_panel"
            actionId.contains("completion") || actionId.contains("inlay") -> "completion"
            actionId.contains("tab") && (
                actionId.contains("inline") || actionId.contains("suggest") || actionId.contains("completion") ||
                    actionId.contains("accept") || actionId.contains("copilot") || actionId.contains("ghost")
                ) -> "completion"
            cls.contains("completion") || cls.contains("inlay") -> "completion"
            else -> "unknown"
        }
    }

    private fun tryExtractFromActionReflection(action: Any?): String? {
        if (action == null) return null
        val raw = deepSearchFields(action,
            listOf("prompt", "query", "message", "instruction", "input",
                "userMessage", "userInput", "request", "chatMessage"),
            maxDepth = 3
        )
        return if (raw != null && !isPlaceholderPrompt(raw)) raw else null
    }

    private fun tryExtractModelReflection(action: Any?): String? {
        if (action == null) return null
        val raw = deepSearchFields(action,
            listOf("model", "modelname", "modelid", "selectedmodel", "llmmodel",
                "chatmodel", "currentmodel", "modelFamily", "modelfamily"),
            maxDepth = 3
        )
        return raw?.let { cleanModelName(it) }
    }

    /**
     * Recursively searches an object's fields (up to maxDepth) for fields matching the given names.
     * Returns the first non-blank string value found.
     */
    private fun deepSearchFields(obj: Any, fieldNames: List<String>, maxDepth: Int, visited: MutableSet<Int> = mutableSetOf()): String? {
        if (maxDepth <= 0) return null
        val id = System.identityHashCode(obj)
        if (id in visited) return null
        visited.add(id)

        try {
            var cls: Class<*>? = obj.javaClass
            while (cls != null && cls != Any::class.java) {
                for (field in cls.declaredFields) {
                    val name = field.name.lowercase()
                    try {
                        field.isAccessible = true
                        if (fieldNames.any { name.contains(it) }) {
                            val value = field.get(obj)
                            val str = value?.toString()
                            if (isValidExtractedValue(str)) return str
                        }
                        // Recurse into non-primitive objects from AI packages
                        if (maxDepth > 1 && !field.type.isPrimitive && field.type != String::class.java) {
                            val value = field.get(obj) ?: continue
                            val valClass = value.javaClass.name.lowercase()
                            if (PACKAGE_TO_PROVIDER.keys.any { valClass.contains(it) }) {
                                val nested = deepSearchFields(value, fieldNames, maxDepth - 1, visited)
                                if (nested != null) return nested
                            }
                        }
                    } catch (_: Throwable) {}
                }
                cls = cls.superclass
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun isValidExtractedValue(str: String?): Boolean {
        if (str.isNullOrBlank() || str.length < 3) return false
        if (str.contains("@") && str.contains(".")) return false  // object toString like "Foo@1a2b3c"
        if (str.startsWith("[") || str.startsWith("{")) return false  // collections/maps
        return true
    }

    /** Reject placeholder/label strings so we store actual user input, not UI constants. */
    private fun isPlaceholderPrompt(str: String?): Boolean {
        if (str.isNullOrBlank()) return true
        val t = str.trim().lowercase()
        if (t.length < 4) return true
        val placeholders = listOf(
            "query", "prompt", "message", "input", "instruction",
            "n/a", "na", "none", "null", "undefined",
            "enter your message", "type a message", "ask me anything",
            "type here", "type your message", "send a message",
            "placeholder", "default", "example"
        )
        if (placeholders.any { t == it }) return true
        if (placeholders.any { t == it.replace(" ", "") }) return true
        if (t.length < 15 && placeholders.any { t.contains(it) }) return true
        return false
    }

    private fun tryExtractFromEditorSelection(event: AnActionEvent): String? {
        try {
            val editor = event.getData(CommonDataKeys.EDITOR) ?: return null
            val selection = editor.selectionModel.selectedText
            if (!selection.isNullOrBlank() && selection.length > 3) {
                return "[selected code] $selection"
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun tryExtractFromChatToolWindow(project: Project): String? {
        try {
            val twm = ToolWindowManager.getInstance(project)
            for (twId in AI_TOOL_WINDOW_IDS) {
                val tw = twm.getToolWindow(twId) ?: continue
                val content = tw.contentManager.selectedContent ?: continue
                val component = content.component
                val text = findLastNonEmptyTextInput(component)
                if (!text.isNullOrBlank() && text.length > 3) return text
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Scans AI chat tool window UIs for the selected model (dropdown, label, or any text that looks like a model name).
     * Checks all contents of each tool window (not only selected tab) so model is found even when chat tab isn't focused.
     */
    private fun tryExtractModelFromChatUI(project: Project): String? {
        try {
            val twm = ToolWindowManager.getInstance(project)
            for (twId in AI_TOOL_WINDOW_IDS) {
                val tw = twm.getToolWindow(twId) ?: continue
                val contentManager = tw.contentManager
                // Prefer selected content first
                val selected = contentManager.selectedContent
                if (selected != null) {
                    val model = findModelInUI(selected.component)
                    if (model != null) return model
                }
                // Then scan all other contents (e.g. other chat tabs) so we don't miss the model dropdown
                for (content in contentManager.contents) {
                    if (content == selected) continue
                    val model = findModelInUI(content.component)
                    if (model != null) return model
                }
            }
            // Fallback: try to get all tool window IDs via reflection (e.g. Copilot may use a different id)
            val allIds = getToolWindowIdsReflection(twm)
            for (twId in allIds) {
                if (twId in AI_TOOL_WINDOW_IDS) continue
                if (!twId.lowercase().contains("copilot") && !twId.lowercase().contains("chat") && !twId.lowercase().contains("codeium") && !twId.lowercase().contains("ai")) continue
                val tw = twm.getToolWindow(twId) ?: continue
                for (content in tw.contentManager.contents) {
                    val model = findModelInUI(content.component)
                    if (model != null) return model
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun getToolWindowIdsReflection(twm: ToolWindowManager): List<String> {
        return try {
            val method = twm.javaClass.getMethod("getToolWindowIds")
            @Suppress("UNCHECKED_CAST")
            (method.invoke(twm) as? Collection<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Walks Swing component tree: collects every JComboBox selectedItem and JLabel text,
     * then returns the first that looks like a real model name (not a package name).
     * Copilot shows the selected model e.g. "GPT5-min" in a dropdown at top of chat.
     */
    private fun findModelInUI(component: Component): String? {
        val candidates = mutableListOf<String>()
        val selectedFirst = mutableListOf<String>() // selected items from combo/list go here to be preferred

        fun addCandidate(s: String?, preferSelected: Boolean = false) {
            val t = s?.trim() ?: return
            if (t.length !in 2..80) return
            val lower = t.lowercase()
            if (preferSelected) selectedFirst.add(t) else candidates.add(t)
        }

        fun walk(c: Component) {
            when (c) {
                is JComboBox<*> -> {
                    val selected = c.selectedItem?.toString()
                    addCandidate(selected, preferSelected = true)
                    try {
                        for (i in 0 until c.itemCount) {
                            val item = c.getItemAt(i)?.toString()
                            if (item != selected) addCandidate(item)
                        }
                    } catch (_: Throwable) {}
                }
                is JList<*> -> {
                    try {
                        val sel = c.selectedValue?.toString()
                        addCandidate(sel, preferSelected = true)
                        val model = c.model
                        for (i in 0 until model.size) {
                            val item = model.getElementAt(i)?.toString()
                            if (item != sel) addCandidate(item)
                        }
                    } catch (_: Throwable) {}
                }
                is JLabel -> {
                    addCandidate(c.text)
                }
                else -> {
                    try {
                        val cls = c.javaClass
                        // Only getText/getLabel — avoid getToolTipText; it can trigger modal progress on status bar etc. when called under write lock
                        for (methodName in listOf("getText", "getLabel")) {
                            try {
                                val m = cls.getMethod(methodName)
                                if (m.parameterCount == 0) {
                                    addCandidate(m.invoke(c)?.toString())
                                }
                            } catch (_: Throwable) {}
                        }
                    } catch (_: Throwable) {}
                }
            }
            if (c is Container) {
                for (child in c.components) walk(child)
            }
        }

        walk(component)

        val ordered = selectedFirst + candidates
        for (candidate in ordered.distinct()) {
            val lower = candidate.lowercase()
            if (looksLikePackageOrClassName(candidate)) continue
            if (lower.contains("(") || lower.contains(")")) continue
            if (matchesKnownModel(lower)) {
                return cleanModelName(candidate)
            }
        }
        return null
    }

    /**
     * Collects text from all JTextComponents (chat input, history, etc.) and returns
     * the one that best matches user input: prefer non-placeholder, longer or multi-word text.
     */
    private fun findLastNonEmptyTextInput(component: Component): String? {
        val candidates = mutableListOf<String>()
        fun walk(c: Component) {
            if (c is JTextComponent) {
                val t = c.text?.trim()
                if (!t.isNullOrBlank() && t.length > 3) candidates.add(t)
            }
            if (c is Container) {
                for (child in c.components) {
                    walk(child)
                }
            }
        }
        walk(component)
        return candidates
            .filter { !isPlaceholderPrompt(it) }
            .maxByOrNull { it.length }
            ?: candidates.firstOrNull()
    }

    /**
     * Scans all loaded classes matching AI provider packages for singleton instances
     * that hold model configuration.
     */
    private fun tryExtractModelFromLoadedClasses(): String? {
        try {
            val classLoader = AiContextExtractor::class.java.classLoader
            val classNames = listOf(
                "com.github.copilot.settings.CopilotSettings",
                "com.github.copilot.settings.CopilotApplicationSettings",
                "com.github.copilot.chat.settings.CopilotChatSettings",
                "com.github.copilot.chat.CopilotChatService",
                "com.github.copilot.completions.CopilotCompletionService",
                "com.github.copilot.CopilotService",
                "com.github.copilot.agent.CopilotAgent",
                "com.github.copilot.agent.CopilotAgentService",
                "com.github.copilot.editor.CopilotEditorManager"
            )
            for (clsName in classNames) {
                try {
                    val cls = Class.forName(clsName, false, classLoader)
                    val instance = tryGetSingletonInstance(cls) ?: continue
                    val model = scanObjectForModel(instance)
                    if (model != null) return model
                } catch (_: ClassNotFoundException) {
                } catch (_: NoClassDefFoundError) {}
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun tryExtractModelFromCopilotSettings(): String? {
        try {
            val classLoader = AiContextExtractor::class.java.classLoader
            val settingsClasses = listOf(
                "com.github.copilot.settings.CopilotSettings",
                "com.github.copilot.settings.CopilotApplicationSettings"
            )
            for (clsName in settingsClasses) {
                try {
                    val cls = Class.forName(clsName, false, classLoader)
                    val instance = tryGetSingletonInstance(cls) ?: continue
                    val model = scanObjectForModel(instance)
                    if (model != null) return model
                } catch (_: ClassNotFoundException) {
                } catch (_: NoClassDefFoundError) {}
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun tryGetSingletonInstance(cls: Class<*>): Any? {
        for (methodName in listOf("getInstance", "getSettings", "get", "getService")) {
            try {
                val method = cls.getMethod(methodName)
                return method.invoke(null)
            } catch (_: Throwable) {}
        }
        try {
            val companion = cls.getDeclaredField("INSTANCE")
            companion.isAccessible = true
            return companion.get(null)
        } catch (_: Throwable) {}
        try {
            val companion = cls.getDeclaredField("Companion")
            companion.isAccessible = true
            val comp = companion.get(null)
            for (m in comp.javaClass.methods) {
                if (m.name == "getInstance" && m.parameterCount == 0) {
                    return m.invoke(comp)
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Scans an object's fields AND getter methods for anything that looks like a model name.
     * Checks both the field/method name and the value content against known model patterns.
     */
    private fun scanObjectForModel(instance: Any, maxDepth: Int = 3, visited: MutableSet<Int> = mutableSetOf()): String? {
        if (maxDepth <= 0) return null
        val id = System.identityHashCode(instance)
        if (id in visited) return null
        visited.add(id)

        val modelFieldHints = listOf("model", "engine", "llm", "deployment", "modelid", "selectedmodel", "chatmodel", "activemodel")

        try {
            var cls: Class<*>? = instance.javaClass
            while (cls != null && cls != Any::class.java) {
                // Check fields
                for (field in cls.declaredFields) {
                    try {
                        field.isAccessible = true
                        val fName = field.name.lowercase()
                        val value = field.get(instance)

                        if (value is String && value.isNotBlank()) {
                            val lower = value.lowercase()
                            if (matchesKnownModel(lower)) {
                                return cleanModelName(value)
                            }
                        }
                        if (value is Enum<*>) {
                            val enumName = value.name.lowercase()
                            if (matchesKnownModel(enumName)) {
                                return cleanModelName(value.name)
                            }
                        }
                        // Recurse into nested objects from AI packages
                        if (maxDepth > 1 && value != null && !field.type.isPrimitive
                            && field.type != String::class.java && field.type != java.lang.Boolean::class.java
                            && field.type != java.lang.Integer::class.java) {
                            val valCls = value.javaClass.name.lowercase()
                            if (modelFieldHints.any { fName.contains(it) } || PACKAGE_TO_PROVIDER.keys.any { valCls.contains(it) }) {
                                val nested = scanObjectForModel(value, maxDepth - 1, visited)
                                if (nested != null) return nested
                            }
                        }
                    } catch (_: Throwable) {}
                }

                // Check getter methods
                for (method in cls.declaredMethods) {
                    if (method.parameterCount != 0) continue
                    val mName = method.name.lowercase()
                    if (!modelFieldHints.any { mName.contains(it) }) continue
                    try {
                        method.isAccessible = true
                        val value = method.invoke(instance)
                        if (value is String && value.isNotBlank()) {
                            val lower = value.lowercase()
                            if (matchesKnownModel(lower)) {
                                return cleanModelName(value)
                            }
                        }
                        if (value is Enum<*>) {
                            val enumName = value.name.lowercase()
                            if (matchesKnownModel(enumName)) {
                                return cleanModelName(value.name)
                            }
                        }
                    } catch (_: Throwable) {}
                }

                cls = cls.superclass
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Cleans up a raw model string: trims, removes surrounding quotes, rejects package/class names.
     */
    private fun cleanModelName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        if (clean.length < 2 || clean.contains("@")) return null
        if (looksLikePackageOrClassName(clean)) return null
        val lower = clean.lowercase()
        if (PACKAGE_TO_PROVIDER.keys.any { lower == it }) return null
        if (lower.contains("(") || lower.contains(")")) return null
        if (!matchesKnownModel(lower)) return null
        return dedupeSlashModelSegments(clean)
    }

    /**
     * Use this before storing or displaying model. Returns null if the value is a package/class name
     * (e.g. "com.github.copilot") so the report never shows it as the AI model.
     */
    fun sanitizeModelForReport(model: String?): String? {
        if (model.isNullOrBlank()) return null
        var trimmed = model.trim()
        if (looksLikePackageOrClassName(trimmed)) return null
        trimmed = dedupeSlashModelSegments(trimmed)
        if (trimmed.length < 2) return null
        val lower = trimmed.lowercase()
        if (!matchesKnownModel(lower)) return null
        return trimmed
    }

    private fun dedupeSlashModelSegments(s: String): String {
        val parts = s.split('/').filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (p in parts) {
            if (out.isNotEmpty() && out.last().equals(p, ignoreCase = true)) continue
            out.add(p)
        }
        return out.joinToString("/")
    }
}
