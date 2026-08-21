package cc.meteormc.bilifix.util

import cc.meteormc.xposedkit.call
import cc.meteormc.xposedkit.reflect
import com.google.protobuf.MessageLite

object ProtobufTransform {
    fun Any.fromHostMessage(): ByteArray? {
        return this.javaClass.reflect.method("toByteArray")?.call(this)
    }

    fun MessageLite.toHostMessage(messageClass: Class<*>): Any? {
        return toByteArray().toHostMessage(messageClass)
    }

    fun ByteArray.toHostMessage(messageClass: Class<*>): Any? {
        return messageClass.reflect.method(
            "parseFrom",
            ByteArray::class.java
        )?.call(null, this)
    }
}