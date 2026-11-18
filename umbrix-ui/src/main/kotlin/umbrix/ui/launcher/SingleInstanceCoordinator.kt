package umbrix.ui.launcher

import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Single-instance coordinator for Umbrix.
 * Prevents multiple JVM instances when users double-click or right-click multiple files,
 * forwarding CLI file paths to the primary running workstation instance.
 */
object SingleInstanceCoordinator {
    private val logger = LoggerFactory.getLogger(SingleInstanceCoordinator::class.java)
    private const val IPC_PORT = 4243
    private const val PROTOCOL_MAGIC = "UMBRIX_OPEN_FILES_V1"

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * Attempts to send [filePaths] to an already running instance.
     * Returns true if sent successfully and acknowledged (meaning this process should terminate).
     * Returns false if no instance is running (meaning this process should become the primary instance).
     */
    fun tryForwardToRunningInstance(filePaths: List<String>): Boolean {
        return try {
            Socket(InetAddress.getLoopbackAddress(), IPC_PORT).use { socket ->
                socket.soTimeout = 2000
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                writer.println(PROTOCOL_MAGIC)
                for (path in filePaths) {
                    writer.println(path)
                }
                writer.println("END")

                val response = reader.readLine()
                if (response == "ACK") {
                    logger.info("Successfully forwarded {} file path(s) to running instance.", filePaths.size)
                    true
                } else {
                    false
                }
            }
        } catch (_: Throwable) {
            // No running instance listening on this port
            false
        }
    }

    /**
     * Starts the IPC server on loopback port 4243.
     * When incoming file paths are received, [onFilesReceived] is invoked.
     */
    fun startServer(onFilesReceived: (List<String>) -> Unit): Boolean {
        return try {
            val s = ServerSocket(IPC_PORT, 50, InetAddress.getLoopbackAddress())
            serverSocket = s
            logger.info("Single-instance IPC server bound to loopback port {}", IPC_PORT)

            executor.submit {
                while (!s.isClosed) {
                    try {
                        val clientSocket = s.accept()
                        handleClient(clientSocket, onFilesReceived)
                    } catch (e: Exception) {
                        if (!s.isClosed) {
                            logger.warn("IPC accept failed", e)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            logger.warn("Could not bind single-instance IPC server on port {}: {}", IPC_PORT, e.message)
            false
        }
    }

    private fun handleClient(clientSocket: Socket, onFilesReceived: (List<String>) -> Unit) {
        executor.submit {
            try {
                clientSocket.use { socket ->
                    socket.soTimeout = 3000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val writer = PrintWriter(socket.getOutputStream(), true)

                    val magic = reader.readLine()
                    if (magic == PROTOCOL_MAGIC) {
                        val receivedPaths = mutableListOf<String>()
                        var line = reader.readLine()
                        while (line != null && line != "END") {
                            if (line.isNotBlank()) {
                                receivedPaths.add(line.trim())
                            }
                            line = reader.readLine()
                        }
                        writer.println("ACK")
                        if (receivedPaths.isNotEmpty()) {
                            onFilesReceived(receivedPaths)
                        }
                    } else {
                        writer.println("ERR_INVALID_PROTOCOL")
                    }
                }
            } catch (e: Exception) {
                logger.warn("Error servicing IPC client", e)
            }
        }
    }

    fun stopServer() {
        try {
            serverSocket?.close()
        } catch (_: Throwable) {}
    }
}
