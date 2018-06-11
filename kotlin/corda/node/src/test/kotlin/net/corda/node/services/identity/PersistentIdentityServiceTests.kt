package net.corda.node.services.identity

import net.corda.core.crypto.Crypto
import net.corda.core.crypto.generateKeyPair
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.services.IdentityService
import net.corda.core.node.services.UnknownAnonymousPartyException
import net.corda.node.internal.configureDatabase
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.crypto.x509Certificates
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.SerializationEnvironmentRule
import net.corda.testing.core.TestIdentity
import net.corda.testing.core.getTestPartyAndCertificate
import net.corda.testing.internal.DEV_INTERMEDIATE_CA
import net.corda.testing.internal.DEV_ROOT_CA
import net.corda.testing.node.MockServices.Companion.makeTestDataSourceProperties
import net.corda.testing.node.makeTestIdentityService
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Tests for the in memory identity service.
 */
class PersistentIdentityServiceTests {
    private companion object {
        val alice = TestIdentity(ALICE_NAME, 70)
        val bob = TestIdentity(BOB_NAME, 80)
        val ALICE get() = alice.party
        val ALICE_IDENTITY get() = alice.identity
        val ALICE_PUBKEY get() = alice.publicKey
        val BOB get() = bob.party
        val BOB_IDENTITY get() = bob.identity
        val BOB_PUBKEY get() = bob.publicKey
    }

    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule()
    private lateinit var database: CordaPersistence
    private lateinit var identityService: IdentityService

    @Before
    fun setup() {
        val identityServiceRef = AtomicReference<IdentityService>()
        // Do all of this in a database transaction so anything that might need a connection has one.
        database = configureDatabase(makeTestDataSourceProperties(), DatabaseConfig(),
                { name -> identityServiceRef.get().wellKnownPartyFromX500Name(name) },
                { party -> identityServiceRef.get().wellKnownPartyFromAnonymous(party) })
        identityService = PersistentIdentityService(DEV_ROOT_CA.certificate, database).also(identityServiceRef::set)
    }

    @After
    fun shutdown() {
        database.close()
    }

    @Test
    fun `get all identities`() {
        // Nothing registered, so empty set
        assertNull(identityService.getAllIdentities().firstOrNull())

        identityService.verifyAndRegisterIdentity(ALICE_IDENTITY)
        var expected = setOf(ALICE)
        var actual = identityService.getAllIdentities().map { it.party }.toHashSet()

        assertEquals(expected, actual)

        // Add a second party and check we get both back
        identityService.verifyAndRegisterIdentity(BOB_IDENTITY)
        expected = setOf(ALICE, BOB)
        actual = identityService.getAllIdentities().map { it.party }.toHashSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `get identity by key`() {
        assertNull(identityService.partyFromKey(ALICE_PUBKEY))
        identityService.verifyAndRegisterIdentity(ALICE_IDENTITY)
        assertEquals(ALICE, identityService.partyFromKey(ALICE_PUBKEY))
        assertNull(identityService.partyFromKey(BOB_PUBKEY))
    }

    @Test
    fun `get identity by name with no registered identities`() {
        assertNull(identityService.wellKnownPartyFromX500Name(ALICE.name))
    }

    @Test
    fun `get identity by substring match`() {
        identityService.verifyAndRegisterIdentity(ALICE_IDENTITY)
        identityService.verifyAndRegisterIdentity(BOB_IDENTITY)
        val alicente = getTestPartyAndCertificate(CordaX500Name(organisation = "Alicente Worldwide", locality = "London", country = "GB"), generateKeyPair().public)
        identityService.verifyAndRegisterIdentity(alicente)
        assertEquals(setOf(ALICE, alicente.party), identityService.partiesFromName("Alice", false))
        assertEquals(setOf(ALICE), identityService.partiesFromName("Alice Corp", true))
        assertEquals(setOf(BOB), identityService.partiesFromName("Bob Plc", true))
    }

    @Test
    fun `get identity by name`() {
        val identities = listOf("Organisation A", "Organisation B", "Organisation C")
                .map { getTestPartyAndCertificate(CordaX500Name(organisation = it, locality = "London", country = "GB"), generateKeyPair().public) }
        assertNull(identityService.wellKnownPartyFromX500Name(identities.first().name))
        identities.forEach {
            identityService.verifyAndRegisterIdentity(it)
        }
        identities.forEach {
            assertEquals(it.party, identityService.wellKnownPartyFromX500Name(it.name))
        }
    }

    /**
     * Generate a certificate path from a root CA, down to a transaction key, store and verify the association.
     */
    @Test
    fun `assert unknown anonymous key is unrecognised`() {
        val rootKey = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
        val rootCert = X509Utilities.createSelfSignedCACertificate(ALICE.name.x500Principal, rootKey)
        val txKey = Crypto.generateKeyPair(X509Utilities.DEFAULT_IDENTITY_SIGNATURE_SCHEME)
        val identity = Party(rootCert)
        val txIdentity = AnonymousParty(txKey.public)

        assertFailsWith<UnknownAnonymousPartyException> {
            identityService.assertOwnership(identity, txIdentity)
        }
    }

    /**
     * Generate a pair of certificate paths from a root CA, down to a transaction key, store and verify the associations.
     * Also checks that incorrect associations are rejected.
     */
    @Test
    fun `get anonymous identity by key`() {
        val (alice, aliceTxIdentity) = createConfidentialIdentity(ALICE.name)
        val (_, bobTxIdentity) = createConfidentialIdentity(ALICE.name)

        // Now we have identities, construct the service and let it know about both
        identityService.verifyAndRegisterIdentity(alice)
        identityService.verifyAndRegisterIdentity(aliceTxIdentity)

        var actual = identityService.certificateFromKey(aliceTxIdentity.party.owningKey)
        assertEquals(aliceTxIdentity, actual!!)

        assertNull(identityService.certificateFromKey(bobTxIdentity.party.owningKey))
        identityService.verifyAndRegisterIdentity(bobTxIdentity)
        actual = identityService.certificateFromKey(bobTxIdentity.party.owningKey)
        assertEquals(bobTxIdentity, actual!!)
    }

    /**
     * Generate a pair of certificate paths from a root CA, down to a transaction key, store and verify the associations.
     * Also checks that incorrect associations are rejected.
     */
    @Test
    fun `assert ownership`() {
        val (alice, anonymousAlice) = createConfidentialIdentity(ALICE.name)
        val (bob, anonymousBob) = createConfidentialIdentity(BOB.name)

        // Now we have identities, construct the service and let it know about both
        identityService.verifyAndRegisterIdentity(anonymousAlice)
        identityService.verifyAndRegisterIdentity(anonymousBob)

        // Verify that paths are verified
        identityService.assertOwnership(alice.party, anonymousAlice.party.anonymise())
        identityService.assertOwnership(bob.party, anonymousBob.party.anonymise())
        assertFailsWith<IllegalArgumentException> {
            identityService.assertOwnership(alice.party, anonymousBob.party.anonymise())
        }
        assertFailsWith<IllegalArgumentException> {
            identityService.assertOwnership(bob.party, anonymousAlice.party.anonymise())
        }

        assertFailsWith<IllegalArgumentException> {
            val owningKey = DEV_INTERMEDIATE_CA.certificate.publicKey
            val subject = CordaX500Name.build(DEV_INTERMEDIATE_CA.certificate.subjectX500Principal)
            identityService.assertOwnership(Party(subject, owningKey), anonymousAlice.party.anonymise())
        }
    }

    @Test
    fun `Test Persistence`() {
        val (alice, anonymousAlice) = createConfidentialIdentity(ALICE.name)
        val (bob, anonymousBob) = createConfidentialIdentity(BOB.name)

        // Register well known identities
        identityService.verifyAndRegisterIdentity(alice)
        identityService.verifyAndRegisterIdentity(bob)
        // Register an anonymous identities
        identityService.verifyAndRegisterIdentity(anonymousAlice)
        identityService.verifyAndRegisterIdentity(anonymousBob)

        // Create new identity service mounted onto same DB
        val newPersistentIdentityService = PersistentIdentityService(DEV_ROOT_CA.certificate, database)

        newPersistentIdentityService.assertOwnership(alice.party, anonymousAlice.party.anonymise())
        newPersistentIdentityService.assertOwnership(bob.party, anonymousBob.party.anonymise())

        val aliceParent = newPersistentIdentityService.wellKnownPartyFromAnonymous(anonymousAlice.party.anonymise())

        assertEquals(alice.party, aliceParent!!)

        val bobReload = newPersistentIdentityService.certificateFromKey(anonymousBob.party.owningKey)
        assertEquals(anonymousBob, bobReload!!)
    }

    private fun createConfidentialIdentity(x500Name: CordaX500Name): Pair<PartyAndCertificate, PartyAndCertificate> {
        val issuerKeyPair = generateKeyPair()
        val issuer = getTestPartyAndCertificate(x500Name, issuerKeyPair.public)
        val txKey = Crypto.generateKeyPair()
        val txCert = X509Utilities.createCertificate(
                CertificateType.CONFIDENTIAL_LEGAL_IDENTITY,
                issuer.certificate,
                issuerKeyPair,
                x500Name.x500Principal,
                txKey.public)
        val txCertPath = X509Utilities.buildCertPath(txCert, issuer.certPath.x509Certificates)
        return Pair(issuer, PartyAndCertificate(txCertPath))
    }

    /**
     * Ensure if we feed in a full identity, we get the same identity back.
     */
    @Test
    fun `deanonymising a well known identity should return the identity`() {
        val service = makeTestIdentityService()
        val expected = ALICE
        service.verifyAndRegisterIdentity(ALICE_IDENTITY)
        val actual = service.wellKnownPartyFromAnonymous(expected)
        assertEquals(expected, actual)
    }

    /**
     * Ensure we don't blindly trust what an anonymous identity claims to be.
     */
    @Test
    fun `deanonymising a false well known identity should return null`() {
        val service = makeTestIdentityService()
        val notAlice = Party(ALICE.name, generateKeyPair().public)
        service.verifyAndRegisterIdentity(ALICE_IDENTITY)
        val actual = service.wellKnownPartyFromAnonymous(notAlice)
        assertNull(actual)
    }
}
