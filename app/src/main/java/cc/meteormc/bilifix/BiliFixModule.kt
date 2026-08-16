package cc.meteormc.bilifix

import android.util.Log
import cc.meteormc.xposedkit.XLog
import cc.meteormc.xposedkit.XposedKit
import cc.meteormc.xposedkit.XposedModule
import cc.meteormc.xposedkit.annotation.ModuleRegister
import cc.meteormc.xposedkit.param.PackageLoadedParam
import cc.meteormc.xposedkit.support.ModuleContextWrapper

@ModuleRegister(
    targetApi = 102,
    staticScope = true
)
class BiliFixModule : XposedModule {
    init {
        XLog.level = if (BuildConfig.DEBUG) Log.VERBOSE else Log.INFO
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        XLog.i("BiliFixModule", "Loading module for package: ${param.packageName}")
        XposedKit.registerAppAttachListener(param.packageName) {
            val resources = it.resources
            val context = BiliFixContext(
                it,
                @OptIn(ExperimentalStdlibApi::class)
                ModuleContextWrapper(
                    it,
                    XposedKit.createModuleResources(
                        resources.displayMetrics,
                        resources.configuration,
                        resources.assets
                    )
                ),
                param.classLoader
            )
        }
    }
}