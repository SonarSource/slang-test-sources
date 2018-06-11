package net.corda.testing.internal.vault

import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.schemas.CommonSchemaV1
import net.corda.core.schemas.MappedSchema
import javax.persistence.*

/**
 * Second version of a cash contract ORM schema that extends the common
 * [VaultLinearState] abstract schema
 */
object DummyLinearStateSchemaV2 : MappedSchema(schemaFamily = DummyLinearStateSchema.javaClass, version = 2,
        mappedTypes = listOf(PersistentDummyLinearState::class.java)) {
    @Entity
    @Table(name = "dummy_linear_states_v2")
    class PersistentDummyLinearState(

            @ElementCollection
            @Column(name = "participants")
            @CollectionTable(name = "dummy_linear_states_v2_parts", joinColumns = [(JoinColumn(name = "output_index", referencedColumnName = "output_index")), (JoinColumn(name = "transaction_id", referencedColumnName = "transaction_id"))])
            override var participants: MutableSet<AbstractParty>? = null,

            @Column(name = "linear_string", nullable = false) var linearString: String,

            @Column(name = "linear_number", nullable = false) var linearNumber: Long,

            @Column(name = "linear_timestamp", nullable = false) var linearTimestamp: java.time.Instant,

            @Column(name = "linear_boolean", nullable = false) var linearBoolean: Boolean,

            @Transient
            val uid: UniqueIdentifier
    ) : CommonSchemaV1.LinearState(uuid = uid.id, externalId = uid.externalId, participants = participants)
}
