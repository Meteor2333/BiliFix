package cc.meteormc.bilifix.feature

import android.app.Activity
import android.content.Context
import android.content.Intent
import cc.meteormc.bilifix.BiliFixContext
import cc.meteormc.bilifix.R
import cc.meteormc.bilifix.ui.PreferencesActivity
import cc.meteormc.bilifix.util.MetadataParser.metadata
import cc.meteormc.xposedkit.findInstances
import cc.meteormc.xposedkit.get
import cc.meteormc.xposedkit.hook.BaseHooker
import cc.meteormc.xposedkit.hook.InvokeCallback
import cc.meteormc.xposedkit.new
import cc.meteormc.xposedkit.reflect
import kotlinx.metadata.jvm.fieldSignature
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object PreferencesEntryInject : BaseHooker<BiliFixContext>() {
    private const val ITEM_URI = "bilibili://bilifix/preference"
    private const val ICON_HOST_URL = "https://i0.hdslb.com/bfs/openplatform/96b801ea0d79ead17867b887c842d70205875bff.png"

    private val routerCache = mutableMapOf<Field, MutableList<Pair<Any, Any?>>>()

    override fun BiliFixContext.hook() {
        val groupReflect = "com.bilibili.lib.homepage.mine.MenuGroup".reflect ?: return
        val groupClass = groupReflect.type
        val itemListField = groupReflect.field("itemList") ?: return

        val item = $$"com.bilibili.lib.homepage.mine.MenuGroup$Item".reflect {
            constructor()?.new<Any>()?.apply {
                field("id")?.set(this, 1 shl 20)
                field("title")?.set(this, moduleContext.getString(R.string.preferences_entry))
                field("uri")?.set(this, ITEM_URI)
                field("icon")?.set(this, ICON_HOST_URL)
//                field("iconResId")?.set(this, R.drawable.ic_preferences_24dp)
                field("visible")?.set(this, 1)
            }
        } ?: return
        "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment".reflect {
            val metadata = type.metadata ?: return@reflect
            val managerField = metadata.properties
                .firstOrNull { it.name == "mPageManager" }
                ?.fieldSignature
                ?.let { field(it.name) }
                ?: return@reflect
            val routerField = managerField.type.reflect {
                fields(Map::class.java).singleOrNull()
            } ?: return@reflect
            fun insertRouter(instance: Any) {
                val manager = managerField[instance] ?: return
                val router = routerField.get<Map<String, Any>>(manager)
                val menuItem = router[ITEM_URI] ?: return
                menuItem.javaClass.reflect {
                    val field = fields.lastOrNull() ?: return@reflect
                    val type = field.type
                    if (!type.isInterface) return@reflect

                    val cache = routerCache.getOrPut(field) { mutableListOf() }
                    cache.add(menuItem to field[menuItem])
                    field.set(
                        menuItem,
                        Proxy.newProxyInstance(
                            classLoader,
                            arrayOf(field.type)
                        ) { proxy, method, args ->
                            val rtnType = method.returnType
                            if (!rtnType.isInterface) return@newProxyInstance null
                            Proxy.newProxyInstance(
                                classLoader,
                                arrayOf(rtnType),
                                ClickHandler(moduleContext)
                            )
                        }
                    )
                }
            }

            method(
                Context::class.java,
                List::class.java,
                null
            )?.apply {
                hookBefore(InvokeCallback.PRIORITY_LOWEST) {
                    val group = it.findArg<List<Any>>().lastOrNull() ?: return@hookBefore
                    if (!groupClass.isInstance(group)) return@hookBefore
                    itemListField.get<MutableList<Any>>(group).add(item)
                }

                hookAfter {
                    insertRouter(it.instance())
                }
            }

            type.findInstances().forEach {
                insertRouter(it)
            }
        }
    }

    override fun unhook() {
        routerCache.forEach { (field, status) ->
            status.forEach { (item, original) -> field.set(item, original) }
        }
        routerCache.clear()
    }

    private class ClickHandler(private val context: Context) : InvocationHandler {
        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<Any?>?
        ): Any? {
            when (method.name) {
                "equals" -> return proxy == args?.firstOrNull()
                "hashCode" -> return System.identityHashCode(proxy)
                "toString" -> return "BiliFixClickHandler"
            }

            val paramTypes = method.parameterTypes
            if (paramTypes.size < 1 || paramTypes[0] != Activity::class.java) {
                return when (method.returnType) {
                    Void::class.javaPrimitiveType -> Unit
                    Boolean::class.javaPrimitiveType -> false
                    Byte::class.javaPrimitiveType -> 0.toByte()
                    Char::class.javaPrimitiveType -> ' '
                    Double::class.javaPrimitiveType -> 0.0
                    Float::class.javaPrimitiveType -> 0F
                    Long::class.javaPrimitiveType -> 0L
                    Short::class.javaPrimitiveType -> 0.toShort()
                    else -> null
                }
            }

            return context.startActivity(
                Intent(context, PreferencesActivity::class.java)
            )
        }
    }
}