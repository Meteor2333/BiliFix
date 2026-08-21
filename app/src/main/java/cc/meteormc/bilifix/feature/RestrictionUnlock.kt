package cc.meteormc.bilifix.feature

import android.os.Build
import android.util.Base64
import cc.meteormc.bilifix.BiliFixContext
import cc.meteormc.bilifix.proto.CommentDetailResponse
import cc.meteormc.bilifix.proto.CommentListResponse
import cc.meteormc.bilifix.proto.Device
import cc.meteormc.bilifix.proto.Metadata
import cc.meteormc.bilifix.proto.Network
import cc.meteormc.bilifix.proto.ReplyInfo
import cc.meteormc.bilifix.proto.ReplyInfoResponse
import cc.meteormc.bilifix.util.MetadataParser.metadata
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

    override fun BiliFixContext.hook() {
        hookAuthorspace()
        hookComment()

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
        val toByteArrayMethod = MessageLite::class.java.name.reflect {
            method("toByteArray")
        } ?: return

        val mossApiClass = $$"com.bilibili.gripper.container.moss.InitMoss$c".clazz ?: return
        val mossApiMetadata = mossApiClass.metadata ?: return
        val mossReflect = mossApiClass.reflect
        val mossInstance by lazy { mossApiClass.findInstances().first() }
        fun <T> mossProperty(name: String): T {
            val property = mossApiMetadata.properties.first { it.name == name }
            return mossReflect.method(property.getterSignature!!.name)!!.call(mossInstance)
        }

        val pkgInfo = hostContext.packageManager.getPackageInfo(hostContext.packageName, 0) ?: return
        "com.bapis.bilibili.main.community.reply.v1.ReplyMoss".reflect {
            fun <T : MessageLite> Method.doHook(
                requestClass: Class<*>,
                methodName: String,
                parseFrom: (ByteArray) -> T,
                defaultInstance: () -> T,
                transform: (from: T, to: T) -> T
            ) = hookBefore {
                val request = it.findArg(requestClass)!!
                val handler = it.findArgIndexed(handlerClass)
                it.args[handler.index] = Proxy.newProxyInstance(
                    classLoader,
                    arrayOf(handlerClass),
                    CommentResponseHandler(
                        this@hookComment,
                        request,
                        methodName,
                        RequestDescriptor(
                            mossProperty("accessKey"),
                            mossProperty("auroraEid"),
                            mossProperty("auroraMid"),
                            mossProperty("buvid"),
                            mossProperty("xtraceId"),

                            mossProperty("appId"),
                            toByteArrayMethod.call(mossProperty("fawkesReq")),
                            mossProperty("fp"),
                            mossProperty("fpLocal"),
                            mossProperty("fpRemote"),
                            mossProperty("fts"),
                            mossProperty("guestId"),
                            toByteArrayMethod.call(mossProperty("locale")),
                            mossProperty("net"),
                            mossProperty("oid"),
                            mossProperty<Enum<*>>("tf").ordinal,

                            "android",
                            "android",
                            "master",
                            pkgInfo.versionName ?: "",
                            @Suppress("DEPRECATION") pkgInfo.versionCode
                        ),
                        parseFrom,
                        defaultInstance,
                        transform,
                        handler.value!!
                    )
                )
            }

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

            method("detailList")?.doHook(
                "com.bapis.bilibili.main.community.reply.v1.DetailListReq".clazz ?: return,
                "DetailList",
                { CommentDetailResponse.parseFrom(it) },
                { CommentDetailResponse.getDefaultInstance() }
            ) { from, to ->
                val builder = to.toBuilder()
                builder.root = replaceLocation(from.root, to.root)
                builder.build()
            }

            method("mainList")?.doHook(
                "com.bapis.bilibili.main.community.reply.v1.MainListReq".clazz ?: return,
                "MainList",
                { CommentListResponse.parseFrom(it) },
                { CommentListResponse.getDefaultInstance() }
            ) { from, to ->
                val builder = to.toBuilder()
                builder.clearReplies()
                to.repliesList.forEachIndexed { index, it ->
                    val reply = from.repliesList.getOrNull(index)
                    builder.addReplies(
                        if (reply != null) replaceLocation(reply, it) else it
                    )
                }
                builder.upTop = replaceLocation(from.upTop, to.upTop)
                builder.adminTop = replaceLocation(from.adminTop, to.adminTop)
                builder.voteTop = replaceLocation(from.voteTop, to.voteTop)
                builder.clearTopReplies()
                to.topRepliesList.forEachIndexed { index, it ->
                    val reply = from.topRepliesList.getOrNull(index)
                    builder.addReplies(
                        if (reply != null) replaceLocation(reply, it) else it
                    )
                }

                builder.build()
            }

            method("replyInfo")?.doHook(
                "com.bapis.bilibili.main.community.reply.v1.ReplyInfoReq".clazz ?: return,
                "ReplyInfo",
                { ReplyInfoResponse.parseFrom(it) },
                { ReplyInfoResponse.getDefaultInstance() }
            ) { from, to ->
                val builder = to.toBuilder()
                builder.reply = replaceLocation(from.reply, to.reply)
                builder.build()
            }
        }
    }

    private fun ByteArray.toBase64(): String {
        return Base64.encodeToString(this, Base64.NO_WRAP)
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
    )

    private class CommentResponseHandler<T : MessageLite>(
        context: HookerContext,
        request: Any,
        method: String,
        descriptor: RequestDescriptor,
        private val parseFrom: (ByteArray) -> T,
        private val defaultInstance: () -> T,
        private val transform: (T, T) -> T,
        private val originHandler: Any
    ) : InvocationHandler {
        private val toBytesMethod: Method
        private lateinit var nextMethod: Method
        private lateinit var completedMethod: Method

        private val extraResponse = AtomicReference<T>()
        private val originResponse = AtomicReference<Any>()

        init {
            with(context) {
                toBytesMethod = MessageLite::class.java.name.reflect {
                    method("toByteArray")
                }!!
            }

            val bytes = toBytesMethod.call<ByteArray>(request)
            val frame = ByteBuffer.allocate(5)
            frame.put(0.toByte())
            frame.putInt(bytes.size)
            val request = Request.Builder()
                .url("https://grpc.biliapi.net/bilibili.main.community.reply.v1.Reply/$method")
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
                    XLog.w(tag, "Failed to fetch comment response", e)
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
                "toString" -> "BiliFixCommentResponseHandler"
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

            val response = parseFrom(toBytesMethod.call(originResponse))
            val newResponse = originResponse.javaClass.reflect {
                method(
                    "parseFrom",
                    ByteArray::class.java
                )?.call<Any>(
                    null,
                    transform(extraResponse, response).toByteArray()
                )
            } ?: return
            nextMethod.call<Unit>(originHandler, newResponse)
            completedMethod.call<Unit>(originHandler)
        }
    }
}