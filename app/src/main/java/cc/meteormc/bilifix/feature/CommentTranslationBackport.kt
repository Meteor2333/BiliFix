package cc.meteormc.bilifix.feature

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.animation.doOnEnd
import cc.meteormc.bilifix.BiliFixContext
import cc.meteormc.bilifix.BiliFixModule
import cc.meteormc.bilifix.R
import cc.meteormc.bilifix.proto.TranslateReplyRequest
import cc.meteormc.bilifix.proto.TranslateReplyResponse
import cc.meteormc.bilifix.util.ProtobufTransform.fromHostMessage
import cc.meteormc.bilifix.util.ProtobufTransform.toHostMessage
import cc.meteormc.xposedkit.XLog
import cc.meteormc.xposedkit.call
import cc.meteormc.xposedkit.findInstances
import cc.meteormc.xposedkit.get
import cc.meteormc.xposedkit.hook.BaseHooker
import cc.meteormc.xposedkit.hook.HookerContext
import cc.meteormc.xposedkit.new
import cc.meteormc.xposedkit.reflect
import cc.meteormc.xposedkit.util.WeakDelegate
import com.google.protobuf.WireFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Proxy
import java.lang.reflect.TypeVariable
import java.util.concurrent.ConcurrentHashMap

object CommentTranslationBackport : BaseHooker<BiliFixContext>() {
    private const val TRANSLATION_SWITCH_FIELD_NUMBER = 37
    private const val FULL_SERVICE_NAME = "bilibili.main.community.reply.v1.Reply"

    private lateinit var translateReplyMethod: Any
    private var mossService by WeakDelegate<Any>()

    private val comments = ConcurrentHashMap<Long, CommentItem>()
    private var heightAnimator: ValueAnimator? = null

    override fun BiliFixContext.hook() {
        val replyControlClass = "com.bapis.bilibili.main.community.reply.v1.ReplyControl".clazz ?: return
        val replyControlUnknownField = replyControlClass.reflect.field("unknownFields") ?: return

        val replyInfoClass = "com.bapis.bilibili.main.community.reply.v1.ReplyInfo".clazz ?: return
        val replyControlMethod = replyInfoClass.reflect.method("getReplyControl") ?: return

        val unknownFieldsReflect = "com.google.protobuf.UnknownFieldSetLite".reflect ?: return
        val unknownCountField = unknownFieldsReflect.field("count") ?: return
        val unknownTagsField = unknownFieldsReflect.field("tags") ?: return
        val unknownObjectsField = unknownFieldsReflect.field("objects") ?: return

        val commentItemClass = "com.bilibili.app.comment3.data.model.CommentItem".clazz ?: return
        "com.bilibili.app.comment3.data.source.v1.b".reflect {
            declaredMethods.filter {
                it.parameterTypes.contains(replyInfoClass) && it.returnType == commentItemClass
            }.hookAfter {
                val commentItem = runCatching {
                    it.result?.toModel(this@hook)
                }.getOrElse { e ->
                    XLog.w(tag, "Failed to convert comment item", e)
                    null
                } ?: return@hookAfter
                val replyInfo = it.findArg(replyInfoClass) ?: return@hookAfter
                val replyControl = replyControlMethod.call<Any>(replyInfo)

                val unknownFields = replyControlUnknownField.get<Any>(replyControl)
                val count = unknownCountField.get<Int>(unknownFields)
                val tags = unknownTagsField.get<IntArray>(unknownFields)
                val objects = unknownObjectsField.get<Array<Any?>>(unknownFields)
                val translationSwitch = (0 until count).firstOrNull { i ->
                    val id = tags[i] ushr 3
                    val wireType = tags[i] and 7
                    id == TRANSLATION_SWITCH_FIELD_NUMBER && wireType == WireFormat.WIRETYPE_VARINT
                }?.let { i ->
                    val number = objects[i] as? Number ?: return@let null
                    TranslationSwitch.entries.firstOrNull { entry -> entry.value == number.toInt() }
                } ?: return@hookAfter
                commentItem.translationSwitch = translationSwitch
                comments[commentItem.id] = commentItem
            }
        }

        "com.bilibili.app.comment3.ui.holder.CommentContentHolder".reflect {
            val itemViewField = field("itemView") ?: return@reflect
            val textHandlerField = fields(
                "com.bilibili.app.comment3.ui.holder.handle.CommentContentRichTextHandler".clazz!!
            ).firstOrNull() ?: return@reflect
            val commentItemField = type.superclass.reflect.declaredFields.firstOrNull {
                val genericType = it.genericType
                genericType is TypeVariable<*> && genericType.name == "DATA"
            } ?: return@reflect
            val actionBarId = hostContext.resources.getIdentifier(
                "item_include_actions",
                "id",
                hostContext.packageName
            )
            val replyButtonId = hostContext.resources.getIdentifier(
                "reply_button",
                "id",
                hostContext.packageName
            )

            constructor(ViewGroup::class.java)?.hookAfter {
                val instance = it.instance ?: return@hookAfter
                val itemView = itemViewField.get<View>(instance)
                if (itemView !is ViewGroup) return@hookAfter

                val actionBar = itemView.findViewById<ViewGroup>(actionBarId) ?: return@hookAfter
                val replyButton = actionBar.findViewById<ImageView>(replyButtonId) ?: return@hookAfter
                val translationButton = ImageButton(moduleContext).apply {
                    tag = "translation_button"
                    background = null
                    visibility = View.GONE
                    minimumWidth = replyButton.minimumWidth
                    minimumHeight = replyButton.minimumHeight
                    setPaddingRelative(
                        replyButton.paddingStart,
                        replyButton.paddingTop,
                        replyButton.paddingEnd,
                        replyButton.paddingBottom
                    )
                    setImageDrawable(
                        moduleContext.getDrawable(R.drawable.ic_translate_16dp)
                    )
                    setOnClickListener { _ ->
                        val commentItem = commentItemField[instance]?.toModel(this@hook) ?: return@setOnClickListener
                        val translationState = when (commentItem.translationState) {
                            TranslationState.ORIGIN -> TranslationState.TRANSLATION
                            TranslationState.TRANSLATION -> TranslationState.ORIGIN
                            else -> return@setOnClickListener
                        }
                        updateTranslation(
                            hostContext,
                            commentItem,
                            textHandlerField.get<Any>(instance),
                            itemView,
                            translationState
                        )
                    }
                    actionBar.addView(
                        this,
                        actionBar.indexOfChild(replyButton).takeIf { n -> n >= 0 }?.plus(1) ?: -1
                    )
                }

                val lp = translationButton.layoutParams
                lp.javaClass.reflect { field("p")?.set(lp, replyButtonId) }
            }

            type.superclass.reflect.declaredMethods.firstOrNull {
                it.genericParameterTypes.contains(commentItemField.genericType)
            }?.let {
                method(it.name, *it.parameterTypes)
            }?.hookAfter {
                val instance = it.instance ?: return@hookAfter
                val itemView = itemViewField.get<View>(instance)
                if (itemView !is ViewGroup) return@hookAfter

                val translationButton = itemView.findViewWithTag<ImageButton>("translation_button") ?: return@hookAfter
                val commentItem = it.findArg(commentItemClass)?.toModel(this@hook) ?: return@hookAfter
                val translationState = when (commentItem.translationSwitch) {
                    TranslationSwitch.SHOW_TRANSLATION -> {
                        TranslationState.ORIGIN
                    }
                    TranslationSwitch.SHOW_ORIGIN -> {
                        TranslationState.TRANSLATION
                    }
                    else -> {
                        translationButton.visibility = View.GONE
                        return@hookAfter
                    }
                }

                translationButton.visibility = View.VISIBLE
                if (commentItem.translatedText == null) {
                    updateTranslation(
                        hostContext,
                        commentItem,
                        textHandlerField.get<Any>(instance),
                        itemView,
                        translationState
                    )
                }
            }
        }
    }

    private fun BiliFixContext.updateTranslation(
        context: Context,
        item: CommentItem,
        handler: Any,
        itemView: View,
        translationState: TranslationState
    ) {
        if (item.translationState == translationState) return
        if (item.translationState == TranslationState.LOADING) return

        val messageView = itemView.findViewById<TextView>(
            hostContext.resources.getIdentifier(
                "comment_message",
                "id",
                hostContext.packageName
            )
        ) ?: return
        when (translationState) {
            TranslationState.ORIGIN -> {
                item.translationState = TranslationState.ORIGIN
                messageView.setTextBackport(this, item.text)
            }
            TranslationState.TRANSLATION -> {
                item.translationState = TranslationState.LOADING
                BiliFixModule.ioScope.launch {
                    val text = fetchTranslation(item, handler)
                    withContext(Dispatchers.Main) {
                        item.translationState = TranslationState.TRANSLATION
                        val origin = messageView.setTextBackport(this@updateTranslation, text)
                        if (origin != null && item.text == null) {
                            item.text = origin
                        }
                    }
                }
            }
            else -> {

            }
        }
    }

    private suspend fun BiliFixContext.fetchTranslation(item: CommentItem, handler: Any) = withContext(Dispatchers.IO) {
        var translated = item.translatedText
        if (translated != null) {
            return@withContext translated
        }

        var serviceRef = mossService
        @Suppress("UNCHECKED_CAST")
        "com.bilibili.lib.moss.api.MossServiceImp".reflect {
            val emptyClass = "com.google.protobuf.Empty".clazz ?: return@reflect
            if (serviceRef == null) {
                serviceRef = type.findInstances().firstOrNull() ?: return@reflect
                mossService = serviceRef
            }

            if (!::translateReplyMethod.isInitialized) {
                fun createMarshaller(
                    name: String,
                    clazz: Class<*>,
                ) = Proxy.newProxyInstance(
                    classLoader,
                    arrayOf(clazz)
                ) { proxy, method, args ->
                    val arg = args.firstOrNull()
                    when (method.name) {
                        "equals" -> proxy == arg
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "BiliFixTranslateReply${name}"
                        else -> if (arg is InputStream) {
                            arg.readBytes().toHostMessage(emptyClass)
                        } else if (InputStream::class.java.isAssignableFrom(method.returnType)) {
                            val bytes = arg?.fromHostMessage() ?: return@newProxyInstance null
                            ByteArrayInputStream(bytes)
                        } else {
                            null
                        }
                    }
                }

                translateReplyMethod = "io.grpc.MethodDescriptor".reflect {
                    val ctor = declaredConstructors.single { !it.isSynthetic }
                    val requestMarshaller = createMarshaller(
                        "RequestMarshaller",
                        ctor.parameterTypes.getOrNull(2) ?: return@reflect null
                    )
                    val responseMarshaller = createMarshaller(
                        "ResponseMarshaller",
                        ctor.parameterTypes.getOrNull(3) ?: return@reflect null
                    )
                    ctor.new(
                        $$"io.grpc.MethodDescriptor$MethodType".reflect {
                            (type as Class<Enum<*>>).enumConstants?.singleOrNull { it.name == "UNARY" }
                        } ?: return@reflect null, // type
                        "$FULL_SERVICE_NAME/TranslateReply", // fullMethodName
                        requestMarshaller, // requestMarshaller
                        responseMarshaller, // responseMarshaller
                        null, // schemaDescriptor
                        false, // idempotent
                        false, // safe
                        true // sampledToLocalTracing
                    )
                } ?: return@reflect
            }

            val request = TranslateReplyRequest.newBuilder()
                .setType(item.type)
                .setOid(item.oid)
                .addRpids(item.id)
                .build()
            val response = method("blockingUnaryCall")?.call<Any>(
                serviceRef,
                translateReplyMethod,
                request.toByteArray().toHostMessage(emptyClass),
                null
            ) ?: return@reflect

            val message = TranslateReplyResponse.parseFrom(response.fromHostMessage())
            val reply = message.translatedRepliesMap[item.id] ?: return@reflect
            val control = reply.replyControl.toHostMessage("com.bapis.bilibili.main.community.reply.v1.ReplyControl".clazz!!)
            val content = reply.translatedContent.toHostMessage("com.bapis.bilibili.main.community.reply.v1.Content".clazz!!)
            translated = "com.bilibili.app.comment3.data.source.v1.b".reflect {
                method(
                    "com.bapis.bilibili.main.community.reply.v1.Content".clazz!!,
                    Long::class.javaPrimitiveType!!,
                    "com.bapis.bilibili.main.community.reply.v1.ReplyControl".clazz!!,
                    Long::class.javaPrimitiveType!!,
                    null
                )?.call<Any>(
                    null,
                    content,
                    item.oid,
                    control,
                    item.id,
                    null
                )
            }?.toSpannableString(this@fetchTranslation, handler)
        }

        val result = translated ?: "Translation failed"
        item.translatedText = result
        return@withContext result
    }

    private fun TextView.setTextBackport(context: HookerContext, text: CharSequence?): CharSequence? {
        val lp = layoutParams
        val oldHeight = height
        val oldText = this.text

        val textView = this@setTextBackport
        with(context) {
            "com.bilibili.app.comment3.ui.widget.ExpandableTextView".reflect {
                if (!type.isInstance(textView)) {
                    textView.text = text
                    return@reflect
                }

                fields(CharSequence::class.java).firstOrNull()?.set(textView, text)
                method("setNeedUpdate")?.call(textView, true)
            }
        }

        measure(
            View.MeasureSpec.makeMeasureSpec(
                width,
                View.MeasureSpec.EXACTLY
            ),
            View.MeasureSpec.makeMeasureSpec(
                0,
                View.MeasureSpec.UNSPECIFIED
            )
        )

        val newHeight = measuredHeight
        lp.height = oldHeight
        layoutParams = lp

        heightAnimator?.cancel()
        heightAnimator = ValueAnimator.ofInt(oldHeight, newHeight).apply {
            addUpdateListener {
                lp.height = it.animatedValue as Int
                layoutParams = lp
            }
            doOnEnd {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                layoutParams = lp
            }
            start()
        }

        return oldText
    }

    private fun Any.toModel(context: BiliFixContext): CommentItem? {
        val item = this
        return with(context) {
            "com.bilibili.app.comment3.data.model.CommentItem".reflect {
                if (!type.isInstance(item)) return@reflect null
                val longFields = fields(Long::class.javaPrimitiveType!!)
                val id = longFields[0].get<Long>(item)
                val oid = longFields[1].get<Long>(item)
                val type = longFields[2].get<Long>(item)
                comments[id] ?: CommentItem(id, oid, type)
            }
        }
    }

    private fun Any.toSpannableString(context: BiliFixContext, handler: Any): CharSequence? {
        val text = this
        return with(context) {
            val richTextClass = "com.bilibili.app.comment3.data.model.q0".clazz ?: return null
            if (!richTextClass.isInstance(text)) return null
            "com.bilibili.app.comment3.ui.holder.handle.CommentContentRichTextHandler".reflect {
                if (!type.isInstance(handler)) return@reflect null
                "com.bilibili.app.comment3.ui.processor.c".reflect process@{
                    val process = this@reflect.fields(type)
                        .singleOrNull()
                        ?.get<Any>(handler)
                        ?: return@process null
                    method(
                        Context::class.java,
                        richTextClass,
                        null,
                        Boolean::class.javaPrimitiveType!!
                    )?.call<CharSequence>(
                        process,
                        hostContext,
                        text,
                        null,
                        false
                    )
                }
            }
        }
    }

    private data class CommentItem(
        val id: Long,
        val oid: Long,
        val type: Long,
        var text: CharSequence? = null,
        var translatedText: CharSequence? = null,
        var translationState: TranslationState = TranslationState.ORIGIN,
        var translationSwitch: TranslationSwitch = TranslationSwitch.UNSPECIFIED
    ) {
        override fun equals(other: Any?): Boolean {
            return other is CommentItem && id == other.id
        }

        override fun hashCode(): Int {
            return id.hashCode()
        }
    }

    private enum class TranslationState {
        LOADING,
        ORIGIN,
        TRANSLATION
    }

    private enum class TranslationSwitch(val value: Int) {
        UNRECOGNIZED(-1),
        UNSPECIFIED(0),
        UNSUPPORTED(1),
        SHOW_TRANSLATION(2),
        SHOW_ORIGIN(3)
    }
}