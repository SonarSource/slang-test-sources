package net.corda.node.services.api

import net.corda.core.contracts.ContractState
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState

//DOCSTART SchemaService
/**
 * A configuration and customisation point for Object Relational Mapping of contract state objects.
 */
interface SchemaService {
    /**
     * Represents any options configured on the node for a schema.
     */
    data class SchemaOptions(val databaseSchema: String? = null, val tablePrefix: String? = null)

    /**
     * Options configured for this node's schemas.  A missing entry for a schema implies all properties are null.
     */
    val schemaOptions: Map<MappedSchema, SchemaOptions>

    /**
     * Given a state, select schemas to map it to that are supported by [generateMappedObject] and that are configured
     * for this node.
     */
    fun selectSchemas(state: ContractState): Iterable<MappedSchema>

    /**
     * Map a state to a [PersistentState] for the given schema, either via direct support from the state
     * or via custom logic in this service.
     */
    fun generateMappedObject(state: ContractState, schema: MappedSchema): PersistentState
}
//DOCEND SchemaService
