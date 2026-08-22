package cc.meteormc.bilifix.feature

import android.content.pm.PackageInfo
import android.os.Build
import android.util.Base64
import cc.meteormc.bilifix.BiliFixContext
import cc.meteormc.bilifix.proto.CommentDetailResponse
import cc.meteormc.bilifix.proto.CommentListResponse
import cc.meteormc.bilifix.proto.Device
import cc.meteormc.bilifix.proto.DmViewResponse
import cc.meteormc.bilifix.proto.Metadata
import cc.meteormc.bilifix.proto.Network
import cc.meteormc.bilifix.proto.ReplyInfo
import cc.meteormc.bilifix.proto.ReplyInfoResponse
import cc.meteormc.bilifix.util.MetadataParser.metadata
import cc.meteormc.bilifix.util.ProtobufTransform.fromHostMessage
import cc.meteormc.bilifix.util.ProtobufTransform.toHostMessage
import cc.meteormc.xposedkit.XLog
import cc.meteormc.xposedkit.call
import cc.meteormc.xposedkit.findInstances
import cc.meteormc.xposedkit.hook.BaseHooker
import cc.meteormc.xposedkit.hook.HookerContext
import cc.meteormc.xposedkit.reflect
import com.google.protobuf.MessageLite
import kotlinx.metadata.jvm.getterSignature
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

object RestrictionUnlock : BaseHooker<BiliFixContext>() {
    private val okhttp = OkHttpClient()

    private var aggressiveMode = false

    override fun BiliFixContext.hook() {
        with(RequestDescriptor) { init() }
        hookAuthorspace()
        hookComment()
        hookSubtitle()

        "com.bilibili.app.authorspace.ui.headerinfo.HeaderInfoMultiLineTags".reflect {
            method("s")?.hookBefore {
                val headerTag = it.findArg<List<*>>().ifEmpty { return@hookBefore }
                "com.bilibili.app.authorspace.api.BiliHeaderTag".reflect {
                    headerTag.forEach { t ->
                        declaredFields.forEach { field ->
                            XLog.d(tag, "${field.name} -> ${field.get(t)}")
                        }
                        XLog.d(tag, "-----------------------------")
                    }
                }
            }
        }
    }

    private fun BiliFixContext.hookAuthorspace() {

    }

    private fun BiliFixContext.hookComment() {
        val handlerClass = "com.bilibili.lib.moss.api.MossResponseHandler".clazz ?: return
        "com.bapis.bilibili.main.community.reply.v1.ReplyMoss".reflect {
            fun replaceLocation(from: ReplyInfo, to: ReplyInfo): ReplyInfo {
                val replyInfoBuilder = to.toBuilder()

                replyInfoBuilder.clearReplies()
                to.repliesList.forEachIndexed { index, it ->
                    val reply = from.repliesList.getOrNull(index)
                    replyInfoBuilder.addReplies(
                        if (reply != null) replaceLocation(reply, it) else it
                    )
                }

                val replyControlBuilder = to.replyControl.toBuilder()
                replyControlBuilder.location = from.replyControl.location
                replyInfoBuilder.replyControl = replyControlBuilder.build()

                return replyInfoBuilder.build()
            }

            method("detailList")?.doHookMoss(
                this@hookComment,
                handlerClass,
                "com.bapis.bilibili.main.community.reply.v1.DetailListReq".clazz ?: return,
                "grpc.biliapi.net",
                "bilibili.main.community.reply.v1.Reply/DetailList",
                { CommentDetailResponse.parseFrom(it) },
                { CommentDetailResponse.getDefaultInstance() }
            ) { from, to ->
                val builder = to.toBuilder()
                builder.root = replaceLocation(from.root, to.root)
                builder.build()
            }

            method("mainList")?.doHookMoss(
                this@hookComment,
                handlerClass,
                "com.bapis.bilibili.main.community.reply.v1.MainListReq".clazz ?: return,
                "grpc.biliapi.net",
                "bilibili.main.community.reply.v1.Reply/MainList",
                { CommentListResponse.parseFrom(it) },
                { CommentListResponse.getDefaultInstance() }
            ) { from, to ->
                // 对于这个请求 不同mobi_app的推流不一致 导致评论列表的顺序受影响
                // 并且它是分页的 一次响应只包含部分评论
                // 这就导致标准版和国际版在同样的页中 返回的内容不一样 所以在国际版看来会丢失部分评论
                // 开启激进模式后 直接使用标准版的响应 可以解决此问题
                // 但与此同时会采用标准版的推流算法
                if (aggressiveMode) {
                    return@doHookMoss from
                }

                val builder = to.toBuilder()

                val repliesList = from.repliesList.associateBy { it.id }
                for (i in 0 until to.repliesCount) {
                    val reply = to.repliesList[i]
                    val target = repliesList[reply.id] ?: continue
                    builder.setReplies(i, replaceLocation(target, reply))
                }

                val topRepliesList = from.topRepliesList.associateBy { it.id }
                for (i in 0 until to.topRepliesCount) {
                    val reply = to.topRepliesList[i]
                    val target = topRepliesList[reply.id] ?: continue
                    builder.setTopReplies(i, replaceLocation(target, reply))
                }

                builder.upTop = replaceLocation(from.upTop, to.upTop)
                builder.adminTop = replaceLocation(from.adminTop, to.adminTop)
                builder.voteTop = replaceLocation(from.voteTop, to.voteTop)
                builder.build()
            }

            method("replyInfo")?.doHookMoss(
                this@hookComment,
                handlerClass,
                "com.bapis.bilibili.main.community.reply.v1.ReplyInfoReq".clazz ?: return,
                "grpc.biliapi.net",
                "bilibili.main.community.reply.v1.Reply/ReplyInfo",
                { ReplyInfoResponse.parseFrom(it) },
                { ReplyInfoResponse.getDefaultInstance() }
            ) { from, to ->
                val builder = to.toBuilder()
                builder.reply = replaceLocation(from.reply, to.reply)
                builder.build()
            }
        }
    }

    private fun BiliFixContext.hookSubtitle() {
        val handlerClass = "com.bilibili.lib.moss.api.MossResponseHandler".clazz ?: return
        "com.bapis.bilibili.community.service.dm.v1.DMMoss".reflect {
            method("dmView")?.doHookMoss(
                this@hookSubtitle,
                handlerClass,
                "com.bapis.bilibili.community.service.dm.v1.DmViewReq".clazz ?: return,
                "app.bilibili.com",
                "bilibili.community.service.dm.v1.DM/DmView",
                { DmViewResponse.parseFrom(it) },
                { DmViewResponse.getDefaultInstance() }
            ) { from, to ->
                val builder = to.toBuilder()
                builder.subtitle = from.subtitle
                builder.build()
            }
        }
    }

    private fun ByteArray.toBase64(): String {
        return Base64.encodeToString(this, Base64.NO_WRAP)
    }

    private fun <T : MessageLite> Method.doHookMoss(
        context: BiliFixContext,
        handlerClass: Class<*>,
        requestClass: Class<*>,
        host: String,
        methodName: String,
        parseFrom: (ByteArray) -> T,
        defaultInstance: () -> T,
        transform: (from: T, to: T) -> T
    ) = with(context) {
        hookBefore {
            val request = it.findArg(requestClass)!!
            val handler = it.findArgIndexed(handlerClass)
            it.args[handler.index] = Proxy.newProxyInstance(
                context.classLoader,
                arrayOf(handlerClass),
                MossResponseHandler(
                    context,
                    request,
                    host,
                    methodName,
                    RequestDescriptor.current(),
                    parseFrom,
                    defaultInstance,
                    transform,
                    handler.value!!
                )
            )
        }
    }

    private data class RequestDescriptor(
        val accessKey: String,
        val auroraEid: String,
        val auroraMid: String,
        val buvid: String,
        val traceId: String,

        val appId: Int,
        val fawkesRequest: ByteArray,
        val fingerprint: String,
        val fingerprintLocal: String,
        val fingerprintRemote: String,
        val fts: Long,
        val guestId: String,
        val locale: ByteArray,
        val network: Int,
        val oid: String,
        val tf: Int,

        val product: String,
        val platform: String,
        val channel: String,
        val versionName: String,
        val versionCode: Int
    ) {
        companion object {
            private lateinit var pkgInfo: PackageInfo
            private lateinit var toBytesMethod: Method
            private lateinit var mossApiClass: Class<*>
            private val mossApiMetadata by lazy { mossApiClass.metadata!! }
            private val mossInstance by lazy { mossApiClass.findInstances().first() }

            fun BiliFixContext.init() {
                pkgInfo = hostContext.packageManager.getPackageInfo(hostContext.packageName, 0)
                toBytesMethod = MessageLite::class.java.name.reflect {
                    method("toByteArray")
                }!!
                mossApiClass = $$"com.bilibili.gripper.container.moss.InitMoss$c".clazz!!
            }

            fun current() = RequestDescriptor(
                mossProperty("accessKey"),
                mossProperty("auroraEid"),
                mossProperty("auroraMid"),
                mossProperty("buvid"),
                mossProperty("xtraceId"),

                mossProperty("appId"),
                mossProperty<Any>("fawkesReq").fromHostMessage()!!,
                mossProperty("fp"),
                mossProperty("fpLocal"),
                mossProperty("fpRemote"),
                mossProperty("fts"),
                mossProperty("guestId"),
                mossProperty<Any>("locale").fromHostMessage()!!,
                mossProperty("net"),
                mossProperty("oid"),
                mossProperty<Enum<*>>("tf").ordinal,

                "android",
                "android",
                "master",
                pkgInfo.versionName ?: "",
                @Suppress("DEPRECATION") pkgInfo.versionCode
            )

            private fun <T> mossProperty(name: String): T {
                val property = mossApiMetadata.properties.first { it.name == name }
                return mossApiClass.reflect.method(property.getterSignature!!.name)!!.call(mossInstance)
            }
        }
    }

    private class MossResponseHandler<T : MessageLite>(
        context: HookerContext,
        request: Any,
        host: String,
        method: String,
        descriptor: RequestDescriptor,
        private val parseFrom: (ByteArray) -> T,
        private val defaultInstance: () -> T,
        private val transform: (T, T) -> T,
        private val originHandler: Any
    ) : InvocationHandler {
        private lateinit var nextMethod: Method
        private lateinit var completedMethod: Method

        private val extraResponse = AtomicReference<T>()
        private val originResponse = AtomicReference<Any>()

        init {
            val bytes = request.fromHostMessage() ?: throw IllegalStateException("Failed to convert request to bytes")
            val frame = ByteBuffer.allocate(5)
            frame.put(0.toByte())
            frame.putInt(bytes.size)
            val request = Request.Builder()
                .url("https://$host/$method")
                .headers(
                    Headers.Builder().apply {
                        add(
                            "User-Agent",
                            buildString {
                                append("Dalvik/${System.getProperty("java.vm.version")} ")
                                append("(Linux; U; Android ${Build.VERSION.RELEASE}; ${Build.MODEL} Build/${Build.ID}) ")
                                append("${descriptor.versionName} ")

                                append("os/${descriptor.platform} ")
                                append("model/${Build.MODEL} ")
                                append("mobi_app/${descriptor.product} ")
                                append("build/${descriptor.versionCode} ")
                                append("channel/${descriptor.channel} ")
                                append("innerVer/${descriptor.versionCode} ")
                                append("osVer/${Build.VERSION.RELEASE} ")
                                append("network/2 ")
                            }
                        )
                        add(
                            "TE",
                            "trailers"
                        )

                        add(
                            "authorization",
                            "identify_v1 ${descriptor.accessKey}"
                        )
                        add(
                            "buvid",
                            descriptor.buvid
                        )

                        add(
                            "x-bili-aurora-eid",
                            descriptor.auroraEid
                        )
                        add(
                            "x-bili-aurora-zone",
                            ""
                        )
                        add(
                            "x-bili-mid",
                            descriptor.auroraMid
                        )
                        add(
                            "x-bili-trace-id",
                            descriptor.traceId
                        )

                        add(
                            "x-bili-fawkes-req-bin",
                            descriptor.fawkesRequest.toBase64()
                        )
                        add(
                            "x-bili-metadata-bin",
                            Metadata.newBuilder()
                                .setAccessKey(descriptor.accessKey)
                                .setMobiApp(descriptor.product)
                                .setDevice("")
                                .setBuild(descriptor.versionCode)
                                .setChannel(descriptor.channel)
                                .setBuvid(descriptor.buvid)
                                .setPlatform(descriptor.platform)
                                .build()
                                .toByteArray()
                                .toBase64()
                        )
                        add(
                            "x-bili-device-bin",
                            Device.newBuilder()
                                .setAppId(descriptor.appId)
                                .setBuild(descriptor.versionCode)
                                .setBuvid(descriptor.buvid)
                                .setMobiApp(descriptor.product)
                                .setPlatform(descriptor.platform)
                                .setDevice("")
                                .setChannel(descriptor.channel)
                                .setBrand(Build.BRAND)
                                .setModel(Build.MODEL)
                                .setOsver(Build.VERSION.RELEASE)
                                .setFpLocal(descriptor.fingerprintLocal)
                                .setFpRemote(descriptor.fingerprintRemote)
                                .setVersionName(descriptor.versionName)
                                .setFp(descriptor.fingerprint)
                                .setFts(descriptor.fts)
                                .setGuestId(descriptor.guestId)
                                .build()
                                .toByteArray()
                                .toBase64()
                        )
                        add(
                            "x-bili-network-bin",
                            Network.newBuilder()
                                .setType(descriptor.network)
                                .setTf(descriptor.tf)
                                .setOid(descriptor.oid)
                                .build()
                                .toByteArray()
                                .toBase64()
                        )
                        add(
                            "x-bili-restriction-bin",
                            ""
                        )
                        add(
                            "x-bili-locale-bin",
                            descriptor.locale.toBase64()
                        )
                        add(
                            "x-bili-exps-bin",
                            ""
                        )
                    }.build()
                )
                .post((frame.array() + bytes).toRequestBody("application/grpc".toMediaType()))
                .build()
            okhttp.newCall(request).enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body.bytes()
                    val message = body.copyOfRange(5, body.size)
                    extraResponse.set(parseFrom(message))
                    tryHandleResponse()
                }

                override fun onFailure(call: Call, e: IOException) {
                    XLog.w(tag, "Failed to fetch moss response", e)
                    extraResponse.set(defaultInstance())
                    tryHandleResponse()
                }
            })
        }

        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<Any?>?
        ): Any? {
            return when (method.name) {
                "equals" -> args?.firstOrNull() == proxy
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "BiliFixMossResponseHandler"
                "onCompleted", "onError" -> {
                    completedMethod = method
                    tryHandleResponse()
                    null
                }
                "onNext" -> {
                    nextMethod = method
                    originResponse.set(args!![0])
                    null
                }
                else -> {
                    method.call(originHandler, *args ?: emptyArray())
                }
            }
        }

        private fun tryHandleResponse() {
            if (!::nextMethod.isInitialized) return
            if (!::completedMethod.isInitialized) return

            val extraResponse = extraResponse.get() ?: return
            val originResponse = originResponse.get() ?: return

            val response = parseFrom(originResponse.fromHostMessage() ?: return)
            val newResponse = transform(extraResponse, response).toHostMessage(originResponse.javaClass) ?: return
            nextMethod.call<Unit>(originHandler, newResponse)
            completedMethod.call<Unit>(originHandler)
        }
    }
}