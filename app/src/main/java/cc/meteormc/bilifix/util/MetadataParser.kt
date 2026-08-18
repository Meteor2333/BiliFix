package cc.meteormc.bilifix.util

import cc.meteormc.xposedkit.call
import cc.meteormc.xposedkit.reflect
import kotlinx.metadata.KmClass
import kotlinx.metadata.jvm.KotlinClassMetadata
import kotlinx.metadata.jvm.Metadata

object MetadataParser {
    val Class<*>.metadata
        get() = parse(this)

    @Suppress("UNCHECKED_CAST")
    fun parse(clazz: Class<*>): KmClass? {
        return clazz.classLoader?.reflect(Metadata::class.java.name) {
            val metadataType = type as? Class<out Annotation>? ?: return@reflect null
            if (!clazz.isAnnotationPresent(metadataType)) return@reflect null

            val annotation = clazz.getDeclaredAnnotation(metadataType)
            Metadata(
                method("k")?.call<Int>(annotation),
                method("mv")?.call<IntArray>(annotation),
                method("d1")?.call<Array<String>>(annotation),
                method("d2")?.call<Array<String>>(annotation),
                method("xs")?.call<String>(annotation),
                method("pn")?.call<String>(annotation),
                method("xi")?.call<Int>(annotation),
            )
        }?.let {
            KotlinClassMetadata.readLenient(it) as? KotlinClassMetadata.Class
        }?.kmClass
    }
}