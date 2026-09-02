package dev.herdr.intellij

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HerdrSettingsTest {
    @Test
    fun `splitter proportion round trips without depending on window width`() {
        val settings = HerdrSettings()

        settings.loadState(HerdrSettings.Preferences(splitterProportion = 0.63f))

        assertEquals(0.63f, settings.state.splitterProportion)
    }

    @Test
    fun `settings trim overrides and normalize both paths to absolute`() {
        val settings = HerdrSettings()
        val overrides = settings.applyOverrides(" ./runtime.sock ", " ./bin/herdr ")

        assertEquals(
            Path
                .of("./runtime.sock")
                .toAbsolutePath()
                .normalize()
                .toString(),
            overrides.socket,
        )
        assertEquals(
            Path
                .of("./bin/herdr")
                .toAbsolutePath()
                .normalize()
                .toString(),
            overrides.executable,
        )
        assertEquals(overrides.socket, settings.state.socketOverride)
        assertEquals(overrides.executable, settings.state.executableOverride)
        assertFailsWith<IllegalArgumentException> {
            settings.applyOverrides("bad\u0000path", "")
        }
    }
}
