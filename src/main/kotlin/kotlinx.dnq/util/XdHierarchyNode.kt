package kotlinx.dnq.util

import kotlinx.dnq.XdEntity
import kotlinx.dnq.XdEntityType
import kotlinx.dnq.link.XdLink
import kotlinx.dnq.simple.XdConstrainedProperty
import java.util.*
import kotlin.reflect.KProperty1

class XdHierarchyNode(val entityType: XdEntityType<*>, val parentNode: XdHierarchyNode?) {

    data class SimpleProperty(val property: KProperty1<*, *>, val delegate: XdConstrainedProperty<*, *>) {
        val dbPropertyName: String
            get() = delegate.dbPropertyName ?: property.name
    }

    data class LinkProperty(val property: KProperty1<*, *>, val delegate: XdLink<*, *>) {
        val dbPropertyName: String
            get() = delegate.dbPropertyName ?: property.name
    }

    val entityConstructor = entityType.entityConstructor

    val children = mutableListOf<XdHierarchyNode>()
    val simpleProperties = LinkedHashMap<KProperty1<*, *>, SimpleProperty>()
    val linkProperties = LinkedHashMap<KProperty1<*, *>, LinkProperty>()

    init {
        parentNode?.children?.add(this)

        val ctor = entityConstructor
        if (ctor != null) {
            initProperties(ctor(FakeEntity))
        }
    }

    private var arePropertiesInited = false

    private fun initProperties(xdFakeEntity: XdEntity) {
        if (arePropertiesInited) return
        parentNode?.initProperties(xdFakeEntity)

        arePropertiesInited = true

        val xdEntityClass = this.entityType.javaClass.enclosingClass
        xdEntityClass.getDelegatedFields().forEach {
            val (property, delegateField) = it
            val delegate = delegateField.get(xdFakeEntity)
            when (delegate) {
                is XdConstrainedProperty<*, *> -> simpleProperties[property] = SimpleProperty(property, delegate)
                is XdLink<*, *> -> linkProperties[property] = LinkProperty(property, delegate)
            }
        }
    }

}
