package dev.herdr.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "HerdrSettings", storages = [Storage("herdr.xml")])
internal class HerdrSettings : PersistentStateComponent<HerdrSettings.Preferences> {
    data class Preferences(
        var socketOverride: String = "",
        var executableOverride: String = "",
        var splitterPosition: Int = 280,
        var compactPresentation: Boolean = false,
    )

    private var preferences = Preferences()

    override fun getState(): Preferences = preferences

    override fun loadState(state: Preferences) {
        preferences = state
    }

    companion object {
        fun getInstance(): HerdrSettings = ApplicationManager.getApplication().getService(HerdrSettings::class.java)
    }
}
