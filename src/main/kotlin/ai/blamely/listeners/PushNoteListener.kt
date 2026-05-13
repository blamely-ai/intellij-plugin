package ai.blamely.listeners

import ai.blamely.git.GitUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * When the user pushes to remote (VCS → Push), also push refs/notes/blamely to origin.
 * Uses reflection to subscribe to git4idea.push.GitPushListener when available (IDE API).
 */
object PushNoteListener {

    private val log = Logger.getInstance(PushNoteListener::class.java)

    private const val GIT_PUSH_LISTENER_CLASS = "git4idea.push.GitPushListener"

    fun register(project: Project) {
        try {
            val listenerClass = Class.forName(GIT_PUSH_LISTENER_CLASS)
            val topicField = listenerClass.getField("TOPIC")
            @Suppress("UNCHECKED_CAST")
            val topic = topicField.get(null) as Topic<Any>
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, _ ->
                if (method.name == "onSuccess") {
                    ApplicationManager.getApplication().executeOnPooledThread {
                        pushNotes(project)
                    }
                }
                null
            }
            project.messageBus.connect(project).subscribe(topic, proxy)
            log.info("Blamely: registered push listener to push blamely notes after push")
        } catch (e: ClassNotFoundException) {
            log.debug("Blamely: GitPushListener not available (older IDE?), use Tools → Blamely → Push AI Notes to Remote after push")
        } catch (e: Throwable) {
            log.debug("Blamely: could not register push listener: ${e.message}")
        }
    }

    private fun pushNotes(project: Project) {
        val repoRoot = GitUtils.getRepoRoot(project) ?: return
        if (GitUtils.pushGitNotes(repoRoot)) {
            log.info("Blamely: pushed refs/notes/blamely to origin after push")
        }
    }
}
