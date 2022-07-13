import jml.lsp.JmlLanguageServer
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.tinylog.Logger
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * @author Alexander Weigl
 * @version 1 (10.07.22)
 */
object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isNotEmpty() && args[0] == "--server")
            runAsServer(args[1].toInt())
        else
            runAsClient(args)
    }

    fun runAsClient(args: Array<String>) {
        try {
            val server = JmlLanguageServer()
            var input: InputStream? = null
            var output: OutputStream? = null
            if (args.isNotEmpty()) {
                val port = args[0]
                val socket = Socket("localhost", port.toInt())
                input = socket.getInputStream()
                output = socket.getOutputStream()
            }


            val launcher = LSPLauncher.createServerLauncher(
                server, input ?: System.`in`, output ?: System.out
            )
            val client: LanguageClient = launcher.remoteProxy
            server.connect(client)
            launcher.startListening()
        } catch (e: Exception) {
            Logger.error(e)
        }
    }

    private fun runAsServer(port: Int) {
        while (true) {
            try {
                ServerSocket(port, 1, InetAddress.getLoopbackAddress()).use { serverSocket ->
                    Logger.info("Listening on {}", serverSocket.localSocketAddress)
                    val socket = serverSocket.accept();
                    val server = JmlLanguageServer()
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val launcher = LSPLauncher.createServerLauncher(
                        server, input ?: System.`in`, output ?: System.out
                    )
                    val client: LanguageClient = launcher.remoteProxy
                    server.connect(client)
                    launcher.startListening()
                }
            } catch (e: Exception) {
                Logger.error(e)
            }
        }
    }
}