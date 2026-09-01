package dev.herdr.intellij

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HerdrConnectionTest {
    @Test
    fun `one shot reads fragmented newline responses and preserves method errors`() {
        ScriptedHerdrServer { index, _, channel ->
            when (index) {
                0 -> channel.writeFragments(
                    "{\"id\":\"ping-1\",\"res",
                    "ult\":{\"type\":\"pong\",\"version\":\"0.7.0\",\"protocol\":22}}\n",
                )
                else -> channel.writeLine(fixture("error.json"))
            }
        }.use { server ->
            val connection = HerdrConnection(server.socketPath)

            assertEquals(22, connection.ping("ping-1").protocol)
            val response = connection.request(HerdrRequest.snapshot("subscribe-1"))
            assertEquals("pane_not_found", assertIs<HerdrResponse.Error>(response).code)
        }
    }

    @Test
    fun `malformed response without newline fails framing`() {
        ScriptedHerdrServer { _, _, channel ->
            channel.writeUtf8("{\"id\":\"ping-1\",\"result\":{\"type\":\"pong\"}}")
        }.use { server ->
            val connection = HerdrConnection(server.socketPath)

            assertFailsWith<HerdrProtocolException> { connection.ping("ping-1") }
        }
    }

    @Test
    fun `subscription acknowledges fragmented framing streams events and reports disconnect`() {
        val event = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        ScriptedHerdrServer { _, _, channel ->
            channel.writeFragments(
                "{\"id\":\"subscribe-1\",\"result\":{\"type\":",
                "\"subscription_started\"}}\n",
            )
            channel.writeLine(fixture("pane-status-event.json"))
        }.use { server ->
            val connection = HerdrConnection(server.socketPath)
            val attempt = connection.subscribe(
                HerdrRequest.combinedSubscription("subscribe-1", setOf("p-agent")),
                onEvent = { if (it is HerdrEvent.PaneStatusChanged) event.countDown() },
                onDisconnect = { disconnected.countDown() },
            )

            assertIs<SubscriptionAttempt.Started>(attempt)
            assertTrue(event.await(2, TimeUnit.SECONDS))
            assertTrue(disconnected.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `mutations distinguish definitely not sent rejected and unknown after write`() {
        val missing = Files.createTempDirectory("herdr-missing").resolve("missing.sock")
        val request = HerdrRequest.mutation(
            "mutate-1",
            "agent.prompt",
            Json.parseToJsonElement("""{"target":"reviewer","text":"hello"}""").jsonObject,
        )
        assertIs<MutationOutcome.DefinitelyNotSent>(HerdrConnection(missing).mutate(request))

        ScriptedHerdrServer { _, _, channel -> channel.writeLine(fixture("error.json")) }.use { server ->
            val rejected = HerdrConnection(server.socketPath).mutate(
                HerdrRequest.mutation("subscribe-1", "agent.prompt", request.params),
            )
            assertEquals("pane_not_found", assertIs<MutationOutcome.Rejected>(rejected).error.code)
        }

        ScriptedHerdrServer { _, _, _ -> Unit }.use { server ->
            assertIs<MutationOutcome.UnknownAfterWrite>(HerdrConnection(server.socketPath).mutate(request))
            assertEquals(1, server.requests.size)
        }

        ClosingDuringWriteHerdrServer().use { server ->
            val largeRequest = HerdrRequest.mutation(
                "mutate-partial",
                "agent.prompt",
                JsonObject(mapOf(
                    "target" to JsonPrimitive("reviewer"),
                    "text" to JsonPrimitive("x".repeat(16 * 1_024 * 1_024)),
                )),
            )

            assertIs<MutationOutcome.UnknownAfterWrite>(
                HerdrConnection(server.socketPath).mutate(largeRequest),
            )
            assertEquals(1, server.acceptedConnections.get())
        }
    }

    @Test
    fun `socket resolution is deterministic and detached start uses path first environment`() {
        val home = Files.createTempDirectory("herdr-home")
        val xdg = Files.createTempDirectory("herdr-xdg")
        val explicit = home.resolve("explicit.sock")
        assertEquals(
            explicit,
            HerdrConnection.resolveSocketTarget(explicit.toString(), mapOf(
                "HERDR_SOCKET_PATH" to home.resolve("environment.sock").toString(),
                "XDG_CONFIG_HOME" to xdg.toString(),
                "HOME" to home.toString(),
            )),
        )
        assertEquals(
            xdg.resolve("herdr/herdr.sock"),
            HerdrConnection.resolveSocketTarget(null, mapOf("XDG_CONFIG_HOME" to xdg.toString(), "HOME" to home.toString())),
        )

        val bin = home.resolve("bin").createDirectories()
        val capture = home.resolve("capture.txt")
        val pathExecutable = bin.resolve("herdr")
        val fallbackExecutable = home.resolve("fallback-herdr")
        val script = """#!/bin/sh
            |printf '%s\n%s\n%s\n' "${'$'}1" "${'$'}HERDR_SOCKET_PATH" "${'$'}{HERDR_SESSION-unset}" > "${'$'}CAPTURE"
            |while :; do sleep 1; done
            |""".trimMargin()
        pathExecutable.writeText(script)
        fallbackExecutable.writeText(script)
        pathExecutable.toFile().setExecutable(true)
        fallbackExecutable.toFile().setExecutable(true)
        val environment = mapOf(
            "PATH" to bin.toString(),
            "CAPTURE" to capture.toString(),
            "HERDR_SESSION" to "ignored-session",
        )
        val connection = HerdrConnection(explicit, environment)
        val started = connection.startHerdr(fallbackExecutable.toString())

        waitUntil(Duration.ofSeconds(2)) { Files.exists(capture) }
        assertEquals(listOf("server", explicit.toString(), "unset"), Files.readAllLines(capture))
        assertEquals(pathExecutable, started.executable)
        connection.close()
        val handle = ProcessHandle.of(started.pid).orElseThrow()
        assertTrue(handle.isAlive)
        handle.destroyForcibly()
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResource("/protocol-22/$name")
    ).readText()

    private fun waitUntil(timeout: Duration, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition did not become true" }
            Thread.sleep(10)
        }
    }
}

internal class ClosingDuringWriteHerdrServer : AutoCloseable {
    private val directory = Files.createTempDirectory("hiw")
    val socketPath: Path = directory.resolve("s")
    private val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
        bind(UnixDomainSocketAddress.of(socketPath))
    }
    val acceptedConnections = AtomicInteger()
    private val serverThread = thread(name = "closing-herdr-server") {
        listener.accept().use { channel ->
            acceptedConnections.incrementAndGet()
            channel.read(ByteBuffer.allocate(1))
        }
    }

    override fun close() {
        listener.close()
        serverThread.join(2_000)
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(directory)
    }
}

internal class ScriptedHerdrServer(
    private val handler: (Int, JsonObject, SocketChannel) -> Unit,
) : AutoCloseable {
    private val directory = Files.createTempDirectory("herdr-intellij-socket")
    val socketPath: Path = directory.resolve("herdr.sock")
    private val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
        bind(UnixDomainSocketAddress.of(socketPath))
    }
    private val running = AtomicBoolean(true)
    private val failures = CopyOnWriteArrayList<Throwable>()
    private val channels = ConcurrentHashMap.newKeySet<SocketChannel>()
    private val connectionThreads = CopyOnWriteArrayList<Thread>()
    private val requestIndex = AtomicInteger()
    val requests = CopyOnWriteArrayList<JsonObject>()
    private val serverThread = thread(name = "scripted-herdr-server") {
        while (running.get()) {
            val channel = try {
                listener.accept()
            } catch (_: Exception) {
                break
            }
            channels += channel
            connectionThreads += thread(name = "scripted-herdr-connection") {
                channel.use {
                    try {
                        val line = Channels.newReader(channel, StandardCharsets.UTF_8).buffered().readLine()
                        if (line != null) {
                            val request = Json.parseToJsonElement(line).jsonObject
                            requests += request
                            handler(requestIndex.getAndIncrement(), request, channel)
                        }
                    } catch (failure: Throwable) {
                        if (running.get()) {
                            failures += failure
                        }
                    } finally {
                        channels -= channel
                    }
                }
            }
        }
    }

    override fun close() {
        running.set(false)
        listener.close()
        channels.forEach {
            try {
                it.close()
            } catch (_: Exception) {
                // Test server teardown.
            }
        }
        serverThread.join(2_000)
        connectionThreads.forEach { it.join(2_000) }
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(directory)
        failures.firstOrNull()?.let { throw AssertionError("scripted server failed", it) }
    }
}

internal fun SocketChannel.writeLine(value: String) {
    writeUtf8(value.trimEnd('\n') + "\n")
}

internal fun SocketChannel.writeFragments(vararg values: String) {
    values.forEach {
        writeUtf8(it)
        Thread.sleep(5)
    }
}

internal fun SocketChannel.writeUtf8(value: String) {
    val bytes = ByteBuffer.wrap(value.toByteArray(StandardCharsets.UTF_8))
    while (bytes.hasRemaining()) {
        write(bytes)
    }
}
