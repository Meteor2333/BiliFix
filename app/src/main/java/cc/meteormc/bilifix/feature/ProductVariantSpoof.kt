package cc.meteormc.bilifix.feature

import cc.meteormc.xposedkit.hook.BaseHooker
import cc.meteormc.xposedkit.hook.HookerContext

object ProductVariantSpoof : BaseHooker<HookerContext>() {
    private var currentVariant = ProductVariant.ANDROID_INTERNATIONAL

    override fun HookerContext.hook() {
        "com.bilibili.lib.foundation.DefaultApps".reflect {
            method("getMobiApp")?.hookBefore {
                it.result = currentVariant.identifier
            }
        }
    }

    private enum class ProductVariant(val identifier: String) {
        ANDROID_DOMESTIC("android"),
        ANDROID_CONCEPT("android_b"),
        ANDROID_INTERNATIONAL("android_i"),
        ANDROID_HD("android_hd"),
        IPHONE("iphone")
    }
}