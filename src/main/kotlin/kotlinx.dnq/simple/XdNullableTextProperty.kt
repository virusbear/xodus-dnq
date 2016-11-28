package kotlinx.dnq.simple

import com.jetbrains.teamsys.dnq.association.PrimitiveAssociationSemantics
import jetbrains.exodus.entitystore.metadata.PropertyType
import kotlinx.dnq.XdEntity
import kotlin.reflect.KProperty

class XdNullableTextProperty<in R : XdEntity>(
        dbPropertyName: String?) :
        XdConstrainedProperty<R, String?>(
                dbPropertyName,
                emptyList(),
                XdPropertyRequirement.OPTIONAL,
                PropertyType.TEXT) {

    override fun getValue(thisRef: R, property: KProperty<*>): String? {
        return PrimitiveAssociationSemantics.getBlobAsString(thisRef.entity, dbPropertyName ?: property.name)
    }

    override fun setValue(thisRef: R, property: KProperty<*>, value: String?) {
        PrimitiveAssociationSemantics.setBlob(thisRef.entity, dbPropertyName ?: property.name, value)
    }

    override fun isDefined(thisRef: R, property: KProperty<*>) = getValue(thisRef, property) != null
}