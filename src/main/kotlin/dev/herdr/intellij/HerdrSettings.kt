package dev.herdr.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.nio.file.Path

internal data class HerdrConnectionOverrides(
    val socket: String,
    val executable: String,
)

@Service(Service.Level.APP)
@State(name = "HerdrSettings", storages = [Storage("herdr.xml")])
internal class HerdrSettings : PersistentStateComponent<HerdrSettings.Preferences> {
    data class Preferences(
        var socketOverride: String = "",
        var executableOverride: String = "",
        var splitterProportion: Float = 0.42f,
        var compactPresentation: Boolean = false,
    )

    private var preferences = Preferences()

    override fun getState(): Preferences = preferences

    override fun loadState(state: Preferences) {
        preferences = state
    }

    fun applyOverrides(
        socket: String,
        executable: String,
    ): HerdrConnectionOverrides {
        val normalized = normalizeOverrides(socket, executable)
        preferences.socketOverride = normalized.socket
        preferences.executableOverride = normalized.executable
        return normalized
    }

    companion object {
        fun getInstance(): HerdrSettings = ApplicationManager.getApplication().getService(HerdrSettings::class.java)

        internal fun normalizeOverrides(
            socket: String,
            executable: String,
        ): HerdrConnectionOverrides {
            val trimmedSocket = socket.trim()
            val normalizedSocket =
                if (trimmedSocket.isEmpty()) {
                    ""
                } else {
                    Path
                        .of(trimmedSocket)
                        .toAbsolutePath()
                        .normalize()
                        .toString()
                }
            val trimmedExecutable = executable.trim()
            val normalizedExecutable =
                if (trimmedExecutable.isEmpty()) {
                    ""
                } else {
                    Path
                        .of(trimmedExecutable)
                        .toAbsolutePath()
                        .normalize()
                        .toString()
                }
            return HerdrConnectionOverrides(normalizedSocket, normalizedExecutable)
        }
    }
}
