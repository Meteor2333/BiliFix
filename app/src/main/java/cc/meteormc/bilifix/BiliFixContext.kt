package cc.meteormc.bilifix

import android.content.Context
import cc.meteormc.xposedkit.hook.HookerContext

class BiliFixContext(
    val hostContext: Context,
    val moduleContext: Context,
    override val classLoader: ClassLoader
) : HookerContext(classLoader)