package ai.blamely.ui

import ai.blamely.cli.CliPaths
import ai.blamely.core.BlameMapService
import ai.blamely.core.BlameUpdateListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities

private const val HEARTBEAT_INTERVAL_SEC = 5L

// JBColor picks the first value on light themes, the second on dark themes —
// mirroring VS Code's ThemeColor('charts.green') / ThemeColor('charts.red').
private val COLOR_RUNNING = JBColor(Color(0x3A7D2C), Color(0x6CCB5F))
private val COLOR_OFFLINE = JBColor(Color(0xB0000D), Color(0xE06C75))

/**
 * Blamely status bar widget — the IntelliJ counterpart of the VS Code StatusBar
 * (vscode-plugin/src/ui/StatusBar.ts). A SINGLE, right-aligned item that combines
 * the daemon lamp with the session-wide AI/Human tally and colors the whole thing
 * green when the daemon is reachable, red when it's offline:
 *
 *   ● 🤖 AI: 20% ≡ 1 | 👤 Human: 80% ≡ 2   (filled lamp + green = daemon up)
 *   ○ 🤖 AI: 20% ≡ 1 | 👤 Human: 80% ≡ 2   (outline lamp + red  = daemon down)
 *
 * Implemented as a CustomStatusBarWidget (a JLabel) so the foreground can be
 * colored — StatusBarWidget.TextPresentation has no color API in the IntelliJ
 * Platform. This replaces the old split of a plain-text tally widget plus a
 * separate daemon-lamp widget, so the two plugins now look the same.
 */
class BlamelyStatusBarWidget(private val project: Project) : CustomStatusBarWidget {

    private val iconLines = "≡"
    private val emptyTally = "🤖 AI: 0% $iconLines 0 | 👤 Human: 0% $iconLines 0"

    @Volatile private var tally: String = emptyTally
    @Volatile private var alive: Boolean = false

    private val label = JLabel().apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                ToolWindowManager.getInstance(project).getToolWindow("Blamely")?.show()
            }
        })
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "blamely-status-ping").also { it.isDaemon = true }
    }
    private var future: ScheduledFuture<*>? = null

    override fun ID(): String = WIDGET_ID

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        // Recompute the tally when attribution data changes. The connection is
        // tied to this widget (a Disposable), so it's torn down with the widget.
        project.messageBus.connect(this).subscribe(
            BlameUpdateListener.TOPIC,
            object : BlameUpdateListener {
                override fun blameUpdated() = scheduleTally()
            },
        )
        scheduleTally()
        // Daemon lamp: a /health heartbeat now, then every 5s.
        ping()
        future = executor.scheduleAtFixedRate(
            ::ping,
            HEARTBEAT_INTERVAL_SEC,
            HEARTBEAT_INTERVAL_SEC,
            TimeUnit.SECONDS,
        )
        repaintLabel()
    }

    /** Tally AI/Human across all files changed this session, off the EDT. */
    private fun scheduleTally() {
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val next = renderSession()
            if (next != tally) {
                tally = next
                repaintLabel()
            }
        }
    }

    private fun renderSession(): String {
        val blameService = project.getService(BlameMapService::class.java) ?: return emptyTally
        val summary = blameService.blameMap.getSummary()
        val total = summary.aiLines + summary.humanLines
        if (total == 0) return emptyTally
        val aiPercent = "%.0f".format((summary.aiLines.toDouble() / total) * 100)
        val humanPercent = "%.0f".format((summary.humanLines.toDouble() / total) * 100)
        return "🤖 AI: $aiPercent% $iconLines ${summary.aiLines} | 👤 Human: $humanPercent% $iconLines ${summary.humanLines}"
    }

    private fun ping() {
        if (project.isDisposed) return
        val nowAlive = checkHealth()
        if (nowAlive != alive) {
            alive = nowAlive
            repaintLabel()
        }
    }

    /** Re-render the label (lamp + tally + color + tooltip) on the EDT. */
    private fun repaintLabel() {
        SwingUtilities.invokeLater {
            if (project.isDisposed) return@invokeLater
            val lamp = if (alive) "●" else "○"
            label.text = "$lamp $tally"
            label.foreground = if (alive) COLOR_RUNNING else COLOR_OFFLINE
            label.toolTipText = if (alive)
                "Blamely daemon running — click for Changes"
            else
                "Blamely daemon offline — run: blamely daemon"
            label.revalidate()
            label.repaint()
        }
    }

    private fun checkHealth(): Boolean {
        val sock = CliPaths.readDaemonSocket()
        if (sock != null) return checkHealthViaSocket(sock)
        val port = CliPaths.readDaemonPort() ?: return false
        return try {
            val conn = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            conn.requestMethod = "GET"
            val ok = conn.responseCode == 200
            conn.disconnect()
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun checkHealthViaSocket(sockPath: String): Boolean {
        return try {
            val req = "GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.UTF_8)
            val addr = java.net.UnixDomainSocketAddress.of(sockPath)
            java.nio.channels.SocketChannel.open(addr).use { ch ->
                ch.configureBlocking(true)
                ch.write(ByteBuffer.wrap(req))
                val resp = ByteBuffer.allocate(256)
                ch.read(resp)
                resp.flip()
                Charsets.UTF_8.decode(resp).toString().startsWith("HTTP/1.1 200")
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun dispose() {
        future?.cancel(false)
        executor.shutdownNow()
    }

    companion object {
        const val WIDGET_ID = "Blamely.BlameStatus"
    }
}
