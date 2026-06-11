package ai.blamely.ui

import ai.blamely.cli.CliPaths
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
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

// JBColor picks the first value on light themes, second on dark themes —
// mirroring VS Code's ThemeColor('charts.green') / ThemeColor('charts.red').
private val COLOR_RUNNING = JBColor(Color(0x3A7D2C), Color(0x6CCB5F))
private val COLOR_OFFLINE = JBColor(Color(0xB0000D), Color(0xE06C75))

/**
 * DaemonStatusBar mirrors VS Code's DaemonStatusBar: a colored ● that turns
 * green when /health responds 200 and red when the daemon is unreachable.
 * Uses CustomStatusBarWidget so the JLabel foreground can be set directly —
 * TextPresentation has no color API in IntelliJ Platform.
 */
class DaemonStatusBar(private val project: Project) : CustomStatusBarWidget {

    private val label = JLabel("● blamely").apply {
        foreground = COLOR_OFFLINE
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Blamely daemon is offline — run `blamely daemon` to start it"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                    .getToolWindow("Blamely")?.show()
            }
        })
    }

    @Volatile private var alive: Boolean = false

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "blamely-daemon-ping").also { it.isDaemon = true }
    }
    private var future: ScheduledFuture<*>? = null

    override fun ID(): String = WIDGET_ID

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        ping()
        future = executor.scheduleAtFixedRate(
            ::ping,
            HEARTBEAT_INTERVAL_SEC,
            HEARTBEAT_INTERVAL_SEC,
            TimeUnit.SECONDS,
        )
    }

    private fun ping() {
        if (project.isDisposed) return
        val nowAlive = checkHealth()
        if (nowAlive != alive) {
            alive = nowAlive
            SwingUtilities.invokeLater {
                if (!project.isDisposed) {
                    label.foreground = if (alive) COLOR_RUNNING else COLOR_OFFLINE
                    label.toolTipText = if (alive)
                        "Blamely daemon is running — click for status"
                    else
                        "Blamely daemon is offline — run `blamely daemon` to start it"
                }
            }
        }
    }

    private fun checkHealth(): Boolean {
        val sock = CliPaths.readDaemonSocket()
        if (sock != null) {
            return checkHealthViaSocket(sock)
        }
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
        const val WIDGET_ID = "Blamely.DaemonStatus"
    }
}
