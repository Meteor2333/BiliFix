package cc.meteormc.bilifix.feature

import cc.meteormc.xposedkit.hook.BaseHooker
import cc.meteormc.xposedkit.hook.HookerContext

object CNVersionToggle : BaseHooker<HookerContext>() {
    override fun HookerContext.hook() {
        "com.bilibili.lib.foundation.DefaultApps".reflect {
            method("getMobiApp")?.hookBefore {
                it.result = "android"
            }
        }
    }
}