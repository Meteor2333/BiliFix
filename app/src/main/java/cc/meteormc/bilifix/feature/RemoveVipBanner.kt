package cc.meteormc.bilifix.feature

import android.content.Context
import android.util.AttributeSet
import cc.meteormc.bilifix.BiliFixContext
import cc.meteormc.xposedkit.hook.BaseHooker

object RemoveVipBanner : BaseHooker<BiliFixContext>() {
    override fun BiliFixContext.hook() {
        "tv.danmaku.bili.ui.main2.mine.widgets.MineVipEntranceView".reflect {
            constructor(
                Context::class.java,
                AttributeSet::class.java,
                Int::class.javaPrimitiveType!!
            )?.hookBefore {
                it.callSuper<Unit>()
                it.doNothing()
            }

            declaredMethods.hookBefore {
                it.doNothing()
            }
        }
    }
}