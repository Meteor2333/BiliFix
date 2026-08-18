package cc.meteormc.bilifix

import android.content.ComponentName
import android.util.Log
import cc.meteormc.bilifix.feature.CommentTranslationBackport
import cc.meteormc.xposedkit.XLog
import cc.meteormc.xposedkit.XposedKit
import cc.meteormc.xposedkit.XposedModule
import cc.meteormc.xposedkit.annotation.ModuleRegister
import cc.meteormc.xposedkit.param.HotReloadingParam
import cc.meteormc.xposedkit.param.PackageLoadedParam
import cc.meteormc.xposedkit.support.ModuleContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@ModuleRegister(
    targetApi = 102,
    staticScope = true,
    autoHotReload = true
)
object BiliFixModule : XposedModule {
    init {
        XLog.level = if (BuildConfig.DEBUG) Log.VERBOSE else Log.INFO
    }

    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        if (param.processName != param.packageName) return

        XLog.i("BiliFixModule", "Loading module for package: ${param.packageName}")
        XposedKit.registerAppAttachListener(param.packageName) {
            val context = BiliFixContext(
                it,
                @OptIn(ExperimentalStdlibApi::class)
                ModuleContextWrapper(it).apply {
                    setProxyActivity(
                        ComponentName(
                            param.packageName,
                            "com.bilibili.app.preferences.BiliPreferencesActivity"
                        )
                    )
                },
                param.classLoader
            )
            CommentTranslationBackport.installHook(context)
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        ioScope.cancel()
        return super.onHotReloading(param)
    }
}