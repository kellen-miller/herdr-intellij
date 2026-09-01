package dev.herdr.intellij

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

internal object HerdrBundle : DynamicBundle("messages.HerdrBundle") {
    fun message(
        @PropertyKey(resourceBundle = "messages.HerdrBundle") key: String,
        vararg params: Any,
    ): String = getMessage(key, *params)
}
