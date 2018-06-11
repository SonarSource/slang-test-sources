package net.corda.testing.internal.vault

import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.CommonSchemaV1
import net.corda.core.schemas.MappedSchema
import javax.persistence.*

/**
 * An object used to fully qualify the [DummyDealStateSchema] family name (i.e. independent of version).
 */
object DummyDealStateSchema

/**
 * First version of a cash contract ORM schema that maps all fields of the [DummyDealState] contract state as it stood
 * at the time of writing.
 */
object DummyDealStateSchemaV1 : MappedSchema(schemaFamily = DummyDealStateSchema.javaClass, version = 1, mappedTypes = listOf(PersistentDummyDealState::class.java)) {
    @Entity
    @Table(name = "dummy_deal_states")
    class PersistentDummyDealState(
            /** parent attributes */
            @ElementCollection
            @Column(name = "participants")
            @CollectionTable(name = "dummy_deal_states_parts", joinColumns = [(JoinColumn(name = "output_index", referencedColumnName = "output_index")), (JoinColumn(name = "transaction_id", referencedColumnName = "transaction_id"))])
            override var participants: MutableSet<AbstractParty>? = null,

            @Transient
            val uid: UniqueIdentifier

    ) : CommonSchemaV1.LinearState(uuid = uid.id, externalId = uid.externalId, participants = participants)
}
