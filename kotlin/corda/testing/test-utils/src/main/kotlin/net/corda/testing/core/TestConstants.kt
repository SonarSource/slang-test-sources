@file:JvmName("TestConstants")

package net.corda.testing.core

import net.corda.core.contracts.Command
import net.corda.core.contracts.TypeOnlyCommandData
import net.corda.core.crypto.generateKeyPair
import net.corda.core.identity.CordaX500Name
import java.security.PublicKey

/** A test notary name **/
@JvmField
val DUMMY_NOTARY_NAME = CordaX500Name("Notary Service", "Zurich", "CH")
/** A test node name **/
@JvmField
val DUMMY_BANK_A_NAME = CordaX500Name("Bank A", "London", "GB")
/** A test node name **/
@JvmField
val DUMMY_BANK_B_NAME = CordaX500Name("Bank B", "New York", "US")
/** A test node name **/
@JvmField
val DUMMY_BANK_C_NAME = CordaX500Name("Bank C", "Tokyo", "JP")
/** A test node name **/
@JvmField
val BOC_NAME = CordaX500Name("BankOfCorda", "London", "GB")
/** A test node name **/
@JvmField
val ALICE_NAME = CordaX500Name("Alice Corp", "Madrid", "ES")
/** A test node name **/
@JvmField
val BOB_NAME = CordaX500Name("Bob Plc", "Rome", "IT")
/** A test node name **/
@JvmField
val CHARLIE_NAME = CordaX500Name("Charlie Ltd", "Athens", "GR")

/** Generates a dummy command that doesn't do anything useful for use in tests **/
fun dummyCommand(vararg signers: PublicKey = arrayOf(generateKeyPair().public)) = Command<TypeOnlyCommandData>(DummyCommandData, signers.toList())

/** Trivial implementation of [TypeOnlyCommandData] for test purposes */
object DummyCommandData : TypeOnlyCommandData()

/** Maximum artemis message size. 10 MiB maximum allowed file size for attachments, including message headers. */
const val MAX_MESSAGE_SIZE: Int = 10485760
