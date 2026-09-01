package dev.herdr.intellij

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class HerdrTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal sealed interface MutationOutcome {
    data class Applied(
        val response: HerdrResponse.Success,
    ) : MutationOutcome

    data class Rejected(
        val error: HerdrResponse.Error,
    ) : MutationOutcome

    data class DefinitelyNotSent(
        val diagnostic: String,
    ) : MutationOutcome

    data class UnknownAfterWrite(
        val diagnostic: String,
    ) : MutationOutcome
}

internal sealed interface SubscriptionAttempt {
    data class Started(
        val subscription: HerdrSubscription,
    ) : SubscriptionAttempt

    data class Rejected(
        val error: HerdrResponse.Error,
    ) : SubscriptionAttempt
}

internal data class StartedHerdr(
    val executable: Path,
    val socketTarget: Path,
    val pid: Long,
)

internal class HerdrConnection(
    socketTarget: Path,
    private val environment: Map<String, String> = System.getenv(),
) : AutoCloseable {
    val socketTarget: Path = socketTarget.toAbsolutePath().normalize()
    private val closed = AtomicBoolean(false)
    private val subscriptions = ConcurrentHashMap.newKeySet<HerdrSubscription>()

    fun ping(id: String): HerdrPong {
        val request = HerdrRequest.ping(id)
        return HerdrProtocol.decodeCompatiblePing(exchangeLine(request), id)
    }

    fun capabilities(id: String): List<AgentCapability> {
        val request = HerdrRequest.capabilities(id)
        return HerdrProtocol.decodeCapabilities(exchangeLine(request), id)
    }

    fun snapshot(id: String): HerdrSnapshot {
        val request = HerdrRequest.snapshot(id)
        return HerdrProtocol.decodeSnapshot(exchangeLine(request), id)
    }

    fun paneRead(
        id: String,
        paneId: String,
    ): HerdrPaneRead {
        val request = HerdrRequest.paneRead(id, paneId)
        return HerdrProtocol.decodePaneRead(exchangeLine(request), id)
    }

    fun request(request: HerdrRequest): HerdrResponse =
        HerdrProtocol.decodeResponse(
            exchangeLine(request),
            request.id,
        )

    fun mutate(request: HerdrRequest): MutationOutcome {
        require(request.mutation) { "mutation outcome requested for a read-only method" }
        var channel: SocketChannel? = null
        var bytesWritten = 0
        return try {
            ensureOpen()
            channel = openChannel()
            bytesWritten = writeRequest(channel, request)
            val line = HerdrLineReader(channel).readLine()
            when (val response = HerdrProtocol.decodeResponse(line, request.id)) {
                is HerdrResponse.Success -> MutationOutcome.Applied(response)
                is HerdrResponse.Error -> MutationOutcome.Rejected(response)
            }
        } catch (failure: Throwable) {
            val diagnostic = failure.message ?: failure::class.java.simpleName
            val actualBytesWritten = (failure as? PartialWriteException)?.bytesWritten ?: bytesWritten
            if (actualBytesWritten == 0) {
                MutationOutcome.DefinitelyNotSent(diagnostic)
            } else {
                MutationOutcome.UnknownAfterWrite(diagnostic)
            }
        } finally {
            closeQuietly(channel)
        }
    }

    fun subscribe(
        request: HerdrRequest,
        onEvent: (HerdrEvent) -> Unit,
        onDisconnect: (String) -> Unit,
    ): SubscriptionAttempt {
        require(request.method == "events.subscribe") { "subscription request must use events.subscribe" }
        ensureOpen()
        val channel =
            try {
                openChannel()
            } catch (failure: Throwable) {
                throw HerdrTransportException("failed to connect to $socketTarget", failure)
            }
        try {
            writeRequest(channel, request)
            val lineReader = HerdrLineReader(channel)
            val response = HerdrProtocol.decodeResponse(lineReader.readLine(), request.id)
            if (response is HerdrResponse.Error) {
                closeQuietly(channel)
                return SubscriptionAttempt.Rejected(response)
            }
            val result = (response as HerdrResponse.Success).result
            if (result != HerdrResult.SubscriptionStarted) {
                throw HerdrProtocolException("events.subscribe did not acknowledge the subscription")
            }

            val subscription = HerdrSubscription(this, channel, lineReader, onEvent, onDisconnect)
            subscriptions += subscription
            subscription.start()
            return SubscriptionAttempt.Started(subscription)
        } catch (failure: Throwable) {
            closeQuietly(channel)
            throw when (failure) {
                is HerdrProtocolException -> failure
                is HerdrTransportException -> failure
                else -> HerdrTransportException("failed to start subscription", failure)
            }
        }
    }

    fun startHerdr(executableOverride: String?): StartedHerdr {
        ensureOpen()
        val executable = resolveExecutable(executableOverride)
        val builder =
            ProcessBuilder(executable.toString(), "server")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment().apply {
            clear()
            putAll(environment)
            remove("HERDR_SESSION")
            put("HERDR_SOCKET_PATH", socketTarget.toString())
        }
        val process =
            try {
                builder.start()
            } catch (failure: IOException) {
                throw HerdrTransportException("failed to start $executable", failure)
            }
        try {
            process.outputStream.close()
        } catch (_: IOException) {
            // The server process is already detached; its stdin is never used.
        }
        return StartedHerdr(executable, socketTarget, process.pid())
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        subscriptions.toList().forEach(HerdrSubscription::close)
        subscriptions.clear()
    }

    internal fun subscriptionClosed(subscription: HerdrSubscription) {
        subscriptions -= subscription
    }

    private fun exchangeLine(request: HerdrRequest): String {
        ensureOpen()
        val channel =
            try {
                openChannel()
            } catch (failure: Throwable) {
                throw HerdrTransportException("failed to connect to $socketTarget", failure)
            }
        channel.use {
            try {
                writeRequest(channel, request)
                return HerdrLineReader(channel).readLine()
            } catch (failure: HerdrTransportException) {
                throw failure
            } catch (failure: HerdrProtocolException) {
                throw failure
            } catch (failure: Throwable) {
                throw HerdrTransportException("request ${request.method} failed", failure)
            }
        }
    }

    private fun openChannel(): SocketChannel =
        SocketChannel.open(StandardProtocolFamily.UNIX).apply {
            configureBlocking(true)
            connect(UnixDomainSocketAddress.of(socketTarget))
        }

    private fun writeRequest(
        channel: SocketChannel,
        request: HerdrRequest,
    ): Int {
        val payload = (HerdrProtocol.encode(request) + "\n").toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.wrap(payload)
        var written = 0
        try {
            while (buffer.hasRemaining()) {
                val originalLimit = buffer.limit()
                buffer.limit(minOf(buffer.position() + WRITE_CHUNK_BYTES, originalLimit))
                written += channel.write(buffer)
                buffer.limit(originalLimit)
            }
            return written
        } catch (failure: IOException) {
            throw PartialWriteException(written, failure)
        }
    }

    private fun resolveExecutable(executableOverride: String?): Path {
        environment["PATH"]?.split(java.io.File.pathSeparatorChar)?.forEach { entry ->
            if (entry.isBlank()) {
                return@forEach
            }
            val candidate =
                Path
                    .of(entry)
                    .resolve("herdr")
                    .toAbsolutePath()
                    .normalize()
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate
            }
        }
        if (!executableOverride.isNullOrBlank()) {
            val candidate = Path.of(executableOverride).toAbsolutePath().normalize()
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate
            }
        }
        throw HerdrTransportException("could not find an executable herdr binary")
    }

    private fun ensureOpen() {
        if (closed.get()) {
            throw HerdrTransportException("Herdr connection is closed")
        }
    }

    private class PartialWriteException(
        val bytesWritten: Int,
        cause: Throwable,
    ) : IOException(cause)

    companion object {
        private const val WRITE_CHUNK_BYTES = 8 * 1_024

        fun resolveSocketTarget(
            socketOverride: String?,
            environment: Map<String, String>,
        ): Path {
            val configured =
                socketOverride?.takeIf(String::isNotBlank)
                    ?: environment["HERDR_SOCKET_PATH"]?.takeIf(String::isNotBlank)
            if (configured != null) {
                return Path.of(configured).toAbsolutePath().normalize()
            }
            val configHome =
                environment["XDG_CONFIG_HOME"]?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: environment["HOME"]?.takeIf(String::isNotBlank)?.let { Path.of(it, ".config") }
                    ?: throw HerdrTransportException("HOME and XDG_CONFIG_HOME are unavailable")
            return configHome.resolve("herdr/herdr.sock").toAbsolutePath().normalize()
        }

        private fun closeQuietly(channel: SocketChannel?) {
            try {
                channel?.close()
            } catch (_: IOException) {
                // The primary transport result already owns the diagnostic.
            }
        }
    }
}

internal class HerdrSubscription(
    private val owner: HerdrConnection,
    private val channel: SocketChannel,
    private val lineReader: HerdrLineReader,
    private val onEvent: (HerdrEvent) -> Unit,
    private val onDisconnect: (String) -> Unit,
) : AutoCloseable {
    private val intentionalClose = AtomicBoolean(false)
    private lateinit var readerThread: Thread

    fun start() {
        readerThread =
            thread(name = "herdr-subscription", isDaemon = true) {
                try {
                    while (!intentionalClose.get()) {
                        onEvent(HerdrProtocol.decodeEvent(lineReader.readLine()))
                    }
                } catch (failure: Throwable) {
                    if (!intentionalClose.get()) {
                        onDisconnect(failure.message ?: "Herdr subscription disconnected")
                    }
                } finally {
                    owner.subscriptionClosed(this)
                    try {
                        channel.close()
                    } catch (_: IOException) {
                        // Subscription state is already terminal.
                    }
                }
            }
    }

    override fun close() {
        if (!intentionalClose.compareAndSet(false, true)) {
            return
        }
        try {
            channel.close()
        } catch (_: IOException) {
            // Intentional cancellation has no user-facing failure.
        }
        if (::readerThread.isInitialized && Thread.currentThread() != readerThread) {
            try {
                readerThread.join(2_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        owner.subscriptionClosed(this)
    }
}

internal class HerdrLineReader(
    private val channel: SocketChannel,
) {
    private val input = ByteBuffer.allocate(4_096).apply { limit(0) }

    fun readLine(): String {
        val output = ByteArrayOutputStream()
        while (true) {
            if (!input.hasRemaining()) {
                input.clear()
                val count =
                    try {
                        channel.read(input)
                    } catch (failure: IOException) {
                        throw HerdrTransportException("socket read failed", failure)
                    }
                if (count < 0) {
                    if (output.size() == 0) {
                        throw HerdrTransportException("socket closed before a response arrived")
                    }
                    throw HerdrProtocolException("socket response ended without newline framing")
                }
                input.flip()
                if (count == 0) {
                    continue
                }
            }

            val byte = input.get()
            if (byte == '\n'.code.toByte()) {
                val bytes =
                    output.toByteArray().let {
                        if (it.lastOrNull() == '\r'.code.toByte()) it.copyOf(it.size - 1) else it
                    }
                return try {
                    StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                } catch (failure: Exception) {
                    throw HerdrProtocolException("message is not valid UTF-8", failure)
                }
            }
            output.write(byte.toInt())
            if (output.size() > MAX_LINE_BYTES) {
                throw HerdrProtocolException("message line exceeds $MAX_LINE_BYTES bytes")
            }
        }
    }

    companion object {
        private const val MAX_LINE_BYTES = 8 * 1_024 * 1_024
    }
}
