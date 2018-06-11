package net.corda.node.services.vault

import net.corda.core.contracts.*
import net.corda.core.crypto.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.packageName
import net.corda.core.node.services.*
import net.corda.core.node.services.vault.*
import net.corda.core.node.services.vault.QueryCriteria.*
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.*
import net.corda.finance.*
import net.corda.finance.contracts.CommercialPaper
import net.corda.finance.contracts.Commodity
import net.corda.finance.contracts.DealState
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.contracts.asset.cash.selection.AbstractCashSelection
import net.corda.finance.schemas.CashSchemaV1
import net.corda.finance.schemas.CashSchemaV1.PersistentCashState
import net.corda.finance.schemas.CommercialPaperSchemaV1
import net.corda.finance.schemas.SampleCashSchemaV2
import net.corda.finance.schemas.SampleCashSchemaV3
import net.corda.node.internal.configureDatabase
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.nodeapi.internal.persistence.DatabaseTransaction
import net.corda.testing.core.*
import net.corda.testing.internal.TEST_TX_TIME
import net.corda.testing.internal.rigorousMock
import net.corda.testing.internal.vault.DUMMY_LINEAR_CONTRACT_PROGRAM_ID
import net.corda.testing.internal.vault.DummyLinearContract
import net.corda.testing.internal.vault.DummyLinearStateSchemaV1
import net.corda.testing.internal.vault.VaultFiller
import net.corda.testing.node.MockServices
import net.corda.testing.node.MockServices.Companion.makeTestDatabaseAndMockServices
import net.corda.testing.node.makeTestIdentityService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.ClassRule
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.ExternalResource
import java.lang.Thread.sleep
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.*

interface VaultQueryParties {
    val alice: TestIdentity
    val bankOfCorda: TestIdentity
    val bigCorp: TestIdentity
    val bob: TestIdentity
    val cashNotary: TestIdentity
    val charlie: TestIdentity
    val dummyCashIssuer: TestIdentity
    val DUMMY_CASH_ISSUER: PartyAndReference
    val dummyNotary: TestIdentity
    val DUMMY_OBLIGATION_ISSUER: Party
    val megaCorp: TestIdentity
    val miniCorp: TestIdentity

    val ALICE get() = alice.party
    val ALICE_IDENTITY get() = alice.identity
    val BIG_CORP get() = bigCorp.party
    val BIG_CORP_IDENTITY get() = bigCorp.identity
    val BOB get() = bob.party
    val BOB_IDENTITY get() = bob.identity
    val BOC get() = bankOfCorda.party
    val BOC_IDENTITY get() = bankOfCorda.identity
    val BOC_KEY get() = bankOfCorda.keyPair
    val BOC_PUBKEY get() = bankOfCorda.publicKey
    val CASH_NOTARY get() = cashNotary.party
    val CASH_NOTARY_IDENTITY get() = cashNotary.identity
    val CHARLIE get() = charlie.party
    val CHARLIE_IDENTITY get() = charlie.identity
    val DUMMY_NOTARY get() = dummyNotary.party
    val DUMMY_NOTARY_KEY get() = dummyNotary.keyPair
    val MEGA_CORP_IDENTITY get() = megaCorp.identity
    val MEGA_CORP_PUBKEY get() = megaCorp.publicKey
    val MEGA_CORP_KEY get() = megaCorp.keyPair
    val MEGA_CORP get() = megaCorp.party
    val MINI_CORP_IDENTITY get() = miniCorp.identity
    val MINI_CORP get() = miniCorp.party

    val services: MockServices
    val vaultFiller: VaultFiller
    val vaultFillerCashNotary: VaultFiller
    val notaryServices: MockServices
    val vaultService: VaultService
    val identitySvc: IdentityService
    val database: CordaPersistence

    val cordappPackages: List<String>
}

open class VaultQueryTestRule : ExternalResource(), VaultQueryParties {

    override val alice = TestIdentity(ALICE_NAME, 70)
    override val bankOfCorda = TestIdentity(BOC_NAME)
    override val bigCorp = TestIdentity(CordaX500Name("BigCorporation", "New York", "US"))
    override val bob = TestIdentity(BOB_NAME, 80)
    override val cashNotary = TestIdentity(CordaX500Name("Cash Notary Service", "Zurich", "CH"), 21)
    override val charlie = TestIdentity(CHARLIE_NAME, 90)
    override val dummyCashIssuer = TestIdentity(CordaX500Name("Snake Oil Issuer", "London", "GB"), 10)
    override val DUMMY_CASH_ISSUER = dummyCashIssuer.ref(1)
    override val dummyNotary = TestIdentity(DUMMY_NOTARY_NAME, 20)
    override val DUMMY_OBLIGATION_ISSUER = TestIdentity(CordaX500Name("Snake Oil Issuer", "London", "GB"), 10).party
    override val megaCorp = TestIdentity(CordaX500Name("MegaCorp", "London", "GB"))
    override val miniCorp = TestIdentity(CordaX500Name("MiniCorp", "London", "GB"))
    override val MINI_CORP get() = miniCorp.party

    override val cordappPackages = listOf(
            "net.corda.testing.contracts",
            "net.corda.finance.contracts",
            CashSchemaV1::class.packageName,
            DummyLinearStateSchemaV1::class.packageName,
            SampleCashSchemaV3::class.packageName)

    override lateinit var services: MockServices
    override lateinit var vaultFiller: VaultFiller
    override lateinit var vaultFillerCashNotary: VaultFiller
    override lateinit var notaryServices: MockServices
    override val vaultService: VaultService get() = services.vaultService
    override lateinit var identitySvc: IdentityService
    override lateinit var database: CordaPersistence


    override fun before() {
        // register additional identities
        val databaseAndServices = makeTestDatabaseAndMockServices(
                cordappPackages,
                makeTestIdentityService(MEGA_CORP_IDENTITY, MINI_CORP_IDENTITY, dummyCashIssuer.identity, dummyNotary.identity),
                megaCorp,
                moreKeys = DUMMY_NOTARY_KEY)
        database = databaseAndServices.first
        services = databaseAndServices.second
        vaultFiller = VaultFiller(services, dummyNotary)
        vaultFillerCashNotary = VaultFiller(services, dummyNotary, CASH_NOTARY)
        notaryServices = MockServices(cordappPackages, dummyNotary, rigorousMock(), dummyCashIssuer.keyPair, BOC_KEY, MEGA_CORP_KEY)
        identitySvc = services.identityService
        // Register all of the identities we're going to use
        (notaryServices.myInfo.legalIdentitiesAndCerts + BOC_IDENTITY + CASH_NOTARY_IDENTITY + MINI_CORP_IDENTITY + MEGA_CORP_IDENTITY).forEach { identity ->
            services.identityService.verifyAndRegisterIdentity(identity)
        }
    }

    override fun after() {
        database.close()
    }
}

class VaultQueryRollbackRule(val vaultQueryParties: VaultQueryParties) : ExternalResource() {

    lateinit var transaction: DatabaseTransaction

    override fun before() {
        transaction = vaultQueryParties.database.newTransaction()
    }

    override fun after() {
        transaction.rollback()
        transaction.close()
    }
}

abstract class VaultQueryTestsBase : VaultQueryParties {

    @Rule
    @JvmField
    val expectedEx: ExpectedException = ExpectedException.none()

    companion object {
        @ClassRule @JvmField
        val testSerialization = SerializationEnvironmentRule()
    }

    /**
     * Helper method for generating a Persistent H2 test database
     */
    @Ignore
    @Test
    fun createPersistentTestDb() {
        val database = configureDatabase(makePersistentDataSourceProperties(), DatabaseConfig(), identitySvc::wellKnownPartyFromX500Name, identitySvc::wellKnownPartyFromAnonymous)
        setUpDb(database, 5000)

        database.close()
    }

    protected fun consumeCash(amount: Amount<Currency>) = vaultFiller.consumeCash(amount, CHARLIE)
    private fun setUpDb(_database: CordaPersistence, delay: Long = 0) {
        _database.transaction {
            // create new states
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 10, DUMMY_CASH_ISSUER)
            val linearStatesXYZ = vaultFiller.fillWithSomeTestLinearStates(1, "XYZ")
            val linearStatesJKL = vaultFiller.fillWithSomeTestLinearStates(2, "JKL")
            vaultFiller.fillWithSomeTestLinearStates(3, "ABC")
            val dealStates = vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))
            // Total unconsumed states = 10 + 1 + 2 + 3 + 3 = 19
            sleep(delay)

            // consume some states
            vaultFiller.consumeLinearStates(linearStatesXYZ.states.toList())
            vaultFiller.consumeLinearStates(linearStatesJKL.states.toList())
            vaultFiller.consumeDeals(dealStates.states.filter { it.state.data.linearId.externalId == "456" })
            consumeCash(50.DOLLARS)
            // Total unconsumed states = 4 + 3 + 2 + 1 (new cash change) = 10
            // Total consumed states = 6 + 1 + 2 + 1 = 10
        }
    }

    private fun makePersistentDataSourceProperties(): Properties {
        val props = Properties()
        props.setProperty("dataSourceClassName", "org.h2.jdbcx.JdbcDataSource")
        props.setProperty("dataSource.url", "jdbc:h2:~/test/vault_query_persistence;DB_CLOSE_ON_EXIT=TRUE")
        props.setProperty("dataSource.user", "sa")
        props.setProperty("dataSource.password", "")
        return props
    }

    /**
     * Query API tests
     */

    /** Generic Query tests
    (combining both FungibleState and LinearState contract types) */

    @Test
    fun `criteria with field from mapped superclass`() {
        database.transaction {
            val expression = builder {
                SampleCashSchemaV2.PersistentCashState::quantity.sum(
                        groupByColumns = listOf(SampleCashSchemaV2.PersistentCashState::currency),
                        orderBy = Sort.Direction.ASC
                )
            }
            val criteria = VaultCustomQueryCriteria(expression)
            vaultService.queryBy<FungibleAsset<*>>(criteria)
        }
    }

    @Test
    fun `criteria with field from mapped superclass of superclass`() {
        database.transaction {
            val expression = builder {
                SampleCashSchemaV2.PersistentCashState::quantity.sum(
                        groupByColumns = listOf(SampleCashSchemaV2.PersistentCashState::currency, SampleCashSchemaV2.PersistentCashState::stateRef),
                        orderBy = Sort.Direction.ASC
                )
            }
            val criteria = VaultCustomQueryCriteria(expression)
            vaultService.queryBy<FungibleAsset<*>>(criteria)
        }
    }

    @Test
    fun `unconsumed states simple`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestLinearStates(10)
            vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))
            // DOCSTART VaultQueryExample1
            val result = vaultService.queryBy<ContractState>()

            /**
             * Query result returns a [Vault.Page] which contains:
             *  1) actual states as a list of [StateAndRef]
             *  2) state reference and associated vault metadata as a list of [Vault.StateMetadata]
             *  3) [PageSpecification] used to delimit the size of items returned in the result set (defaults to [DEFAULT_PAGE_SIZE])
             *  4) Total number of items available (to aid further pagination if required)
             */
            val states = result.states
            val metadata = result.statesMetadata

            // DOCEND VaultQueryExample1
            assertThat(states).hasSize(16)
            assertThat(metadata).hasSize(16)
        }
    }

    @Test
    fun `unconsumed states verbose`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestLinearStates(10)
            vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))
            val criteria = VaultQueryCriteria() // default is UNCONSUMED
            val result = vaultService.queryBy<ContractState>(criteria)

            assertThat(result.states).hasSize(16)
            assertThat(result.statesMetadata).hasSize(16)
        }
    }

    @Test
    fun `unconsumed states with count`() {
        database.transaction {
            repeat(4) {
                vaultFiller.fillWithSomeTestCash(25.DOLLARS, notaryServices, 1, DUMMY_CASH_ISSUER)
            }
            val paging = PageSpecification(DEFAULT_PAGE_NUM, 10)
            val criteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            val resultsBeforeConsume = vaultService.queryBy<ContractState>(criteria, paging)
            assertThat(resultsBeforeConsume.states).hasSize(4)
            assertThat(resultsBeforeConsume.totalStatesAvailable).isEqualTo(4)
            consumeCash(75.DOLLARS)
            val consumedCriteria = VaultQueryCriteria(status = Vault.StateStatus.UNCONSUMED)
            val resultsAfterConsume = vaultService.queryBy<ContractState>(consumedCriteria, paging)
            assertThat(resultsAfterConsume.states).hasSize(1)
            assertThat(resultsAfterConsume.totalStatesAvailable).isEqualTo(1)
        }
    }

    @Test
    fun `unconsumed cash states simple`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestLinearStates(10)
            vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))
            val result = vaultService.queryBy<Cash.State>()

            assertThat(result.states).hasSize(3)
            assertThat(result.statesMetadata).hasSize(3)
        }
    }

    @Test
    fun `unconsumed cash states verbose`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestLinearStates(10)
            vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))
            val criteria = VaultQueryCriteria() // default is UNCONSUMED
            val result = vaultService.queryBy<Cash.State>(criteria)

            assertThat(result.states).hasSize(3)
            assertThat(result.statesMetadata).hasSize(3)
        }
    }

    @Test
    fun `unconsumed cash states sorted by state ref`() {
        val stateRefs: MutableList<StateRef> = mutableListOf()
        database.transaction {
            val issuedStates = vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 10, DUMMY_CASH_ISSUER)
            val issuedStateRefs = issuedStates.states.map { it.ref }.toList()
            stateRefs.addAll(issuedStateRefs)
            this.session.flush()

            val spentStates = consumeCash(25.DOLLARS)
            val consumedStateRefs = spentStates.consumed.map { it.ref }.toList()
            val producedStateRefs = spentStates.produced.map { it.ref }.toList()
            stateRefs.addAll(consumedStateRefs.plus(producedStateRefs))

            val sortAttribute = SortAttribute.Standard(Sort.CommonStateAttribute.STATE_REF)
            val criteria = VaultQueryCriteria()
            val results = vaultService.queryBy<Cash.State>(criteria, Sort(setOf(Sort.SortColumn(sortAttribute, Sort.Direction.ASC))))

            // default StateRef sort is by index then txnId:
            // order by
            //    vaultschem1_.output_index,
            //    vaultschem1_.transaction_id asc
            assertThat(results.states).hasSize(8)       // -3 CONSUMED + 1 NEW UNCONSUMED (change)

            val sortedStateRefs = stateRefs.sortedBy { it.index }

            assertThat(results.states.first().ref.index).isEqualTo(sortedStateRefs.first().index)   // 0
            assertThat(results.states.last().ref.index).isEqualTo(sortedStateRefs.last().index)     // 1
        }
    }

    @Test
    fun `unconsumed cash states sorted by state ref txnId and index`() {
        val consumed = mutableSetOf<SecureHash>()
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 10, DUMMY_CASH_ISSUER)
            this.session.flush()

            consumeCash(10.DOLLARS).consumed.forEach { consumed += it.ref.txhash }
            consumeCash(10.DOLLARS).consumed.forEach { consumed += it.ref.txhash }
            val sortAttributeTxnId = SortAttribute.Standard(Sort.CommonStateAttribute.STATE_REF_TXN_ID)
            val sortAttributeIndex = SortAttribute.Standard(Sort.CommonStateAttribute.STATE_REF_INDEX)
            val sortBy = Sort(setOf(Sort.SortColumn(sortAttributeTxnId, Sort.Direction.ASC),
                    Sort.SortColumn(sortAttributeIndex, Sort.Direction.ASC)))
            val criteria = VaultQueryCriteria()
            val results = vaultService.queryBy<Cash.State>(criteria, sortBy)

            results.statesMetadata.forEach {
                println(" ${it.ref}")
                assertThat(it.status).isEqualTo(Vault.StateStatus.UNCONSUMED)
            }
            val sorted = results.states.sortedBy { it.ref.toString() }
            assertThat(results.states).isEqualTo(sorted)
            assertThat(results.states).allSatisfy { !consumed.contains(it.ref.txhash) }
        }
    }

    @Test
    fun `unconsumed states for state refs`() {
        database.transaction {
            vaultFiller.fillWithSomeTestLinearStates(8)
            val issuedStates = vaultFiller.fillWithSomeTestLinearStates(2)
            val stateRefs = issuedStates.states.map { it.ref }.toList()

            // DOCSTART VaultQueryExample2
            val sortAttribute = SortAttribute.Standard(Sort.CommonStateAttribute.STATE_REF_TXN_ID)
            val criteria = VaultQueryCriteria(stateRefs = listOf(stateRefs.first(), stateRefs.last()))
            val results = vaultService.queryBy<DummyLinearContract.State>(criteria, Sort(setOf(Sort.SortColumn(sortAttribute, Sort.Direction.ASC))))
            // DOCEND VaultQueryExample2

            assertThat(results.states).hasSize(2)

            val sortedStateRefs = stateRefs.sortedBy { it.txhash.bytes.toHexString() }
            assertThat(results.states.first().ref).isEqualTo(sortedStateRefs.first())
            assertThat(results.states.last().ref).isEqualTo(sortedStateRefs.last())
        }
    }

    @Test
    fun `unconsumed states for contract state types`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestLinearStates(10)
            vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))
            // default State.Status is UNCONSUMED
            // DOCSTART VaultQueryExample3
            val criteria = VaultQueryCriteria(contractStateTypes = setOf(Cash.State::class.java, DealState::class.java))
            val results = vaultService.queryBy<ContractState>(criteria)
            // DOCEND VaultQueryExample3
            assertThat(results.states).hasSize(6)
        }
    }

    @Test
    fun `consumed states`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            val linearStates = vaultFiller.fillWithSomeTestLinearStates(2, "TEST") // create 2 states with same externalId
            vaultFiller.fillWithSomeTestLinearStates(8)
            val dealStates = vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))
            vaultFiller.consumeLinearStates(linearStates.states.toList())
            vaultFiller.consumeDeals(dealStates.states.filter { it.state.data.linearId.externalId == "456" })
            consumeCash(50.DOLLARS)
            val criteria = VaultQueryCriteria(status = Vault.StateStatus.CONSUMED)
            val results = vaultService.queryBy<ContractState>(criteria)
            assertThat(results.states).hasSize(5)
        }
    }

    @Test
    fun `consumed states with count`() {
        database.transaction {
            repeat(4) {
                vaultFiller.fillWithSomeTestCash(25.DOLLARS, notaryServices, 1, DUMMY_CASH_ISSUER)
            }
            val paging = PageSpecification(DEFAULT_PAGE_NUM, 10)
            val criteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            val resultsBeforeConsume = vaultService.queryBy<ContractState>(criteria, paging)
            assertThat(resultsBeforeConsume.states).hasSize(4)
            assertThat(resultsBeforeConsume.totalStatesAvailable).isEqualTo(4)
            consumeCash(75.DOLLARS)
            val consumedCriteria = VaultQueryCriteria(status = Vault.StateStatus.CONSUMED)
            val resultsAfterConsume = vaultService.queryBy<ContractState>(consumedCriteria, paging)
            assertThat(resultsAfterConsume.states).hasSize(3)
            assertThat(resultsAfterConsume.totalStatesAvailable).isEqualTo(3)
        }
    }

    @Test
    fun `all states`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            val linearStates = vaultFiller.fillWithSomeTestLinearStates(2, "TEST") // create 2 results with same UID
            vaultFiller.fillWithSomeTestLinearStates(8)
            val dealStates = vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))

            vaultFiller.consumeLinearStates(linearStates.states.toList())
            vaultFiller.consumeDeals(dealStates.states.filter { it.state.data.linearId.externalId == "456" })

            consumeCash(50.DOLLARS) // generates a new change state!

            val criteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            val results = vaultService.queryBy<ContractState>(criteria)
            assertThat(results.states).hasSize(17)
        }
    }

    @Test
    fun `all states with count`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 1, DUMMY_CASH_ISSUER)
            val criteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            val paging = PageSpecification(DEFAULT_PAGE_NUM, 10)

            val resultsBeforeConsume = vaultService.queryBy<ContractState>(criteria, paging)
            assertThat(resultsBeforeConsume.states).hasSize(1)
            assertThat(resultsBeforeConsume.totalStatesAvailable).isEqualTo(1)
            consumeCash(50.DOLLARS)    // consumed 100 (spent), produced 50 (change)
            val resultsAfterConsume = vaultService.queryBy<ContractState>(criteria, paging)
            assertThat(resultsAfterConsume.states).hasSize(2)
            assertThat(resultsAfterConsume.totalStatesAvailable).isEqualTo(2)
        }
    }

    @Test
    fun `unconsumed states by notary`() {
        database.transaction {
            vaultFillerCashNotary.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestLinearStates(10)
            vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))
            // DOCSTART VaultQueryExample4
            val criteria = VaultQueryCriteria(notary = listOf(CASH_NOTARY))
            val results = vaultService.queryBy<ContractState>(criteria)
            // DOCEND VaultQueryExample4
            assertThat(results.states).hasSize(3)
        }
    }

    @Test
    fun `unconsumed linear states for single participant`() {
        database.transaction {
            identitySvc.verifyAndRegisterIdentity(BIG_CORP_IDENTITY)
            vaultFiller.fillWithSomeTestLinearStates(2, "TEST", participants = listOf(MEGA_CORP, MINI_CORP))
            vaultFiller.fillWithSomeTestDeals(listOf("456"), participants = listOf(MEGA_CORP, BIG_CORP))
            vaultFiller.fillWithSomeTestDeals(listOf("123", "789"), participants = listOf(BIG_CORP))
            val criteria = LinearStateQueryCriteria(participants = listOf(BIG_CORP))
            val results = vaultService.queryBy<ContractState>(criteria)
            assertThat(results.states).hasSize(3)
        }
    }

    @Test
    fun `unconsumed linear states for two participants`() {
        database.transaction {
            identitySvc.verifyAndRegisterIdentity(BIG_CORP_IDENTITY)
            vaultFiller.fillWithSomeTestLinearStates(2, "TEST", participants = listOf(MEGA_CORP, MINI_CORP))
            vaultFiller.fillWithSomeTestDeals(listOf("456"), participants = listOf(MEGA_CORP, BIG_CORP))
            vaultFiller.fillWithSomeTestDeals(listOf("123", "789"), participants = listOf(MEGA_CORP))
            // DOCSTART VaultQueryExample5
            val criteria = LinearStateQueryCriteria(participants = listOf(BIG_CORP, MINI_CORP))
            val results = vaultService.queryBy<ContractState>(criteria)
            // DOCEND VaultQueryExample5

            assertThat(results.states).hasSize(3)
        }
    }

    @Test
    fun `unconsumed states with soft locking`() {
        database.transaction {
            val issuedStates = vaultFillerCashNotary.fillWithSomeTestCash(100.DOLLARS, notaryServices, 10, DUMMY_CASH_ISSUER).states.toList()
            vaultService.softLockReserve(UUID.randomUUID(), NonEmptySet.of(issuedStates[1].ref, issuedStates[2].ref, issuedStates[3].ref))
            val lockId1 = UUID.randomUUID()
            vaultService.softLockReserve(lockId1, NonEmptySet.of(issuedStates[4].ref, issuedStates[5].ref))
            val lockId2 = UUID.randomUUID()
            vaultService.softLockReserve(lockId2, NonEmptySet.of(issuedStates[6].ref))

            // excluding soft locked states
            val criteriaExclusive = VaultQueryCriteria(softLockingCondition = SoftLockingCondition(SoftLockingType.UNLOCKED_ONLY))
            val resultsExclusive = vaultService.queryBy<ContractState>(criteriaExclusive)
            assertThat(resultsExclusive.states).hasSize(4)

            // only soft locked states
            val criteriaLockedOnly = VaultQueryCriteria(softLockingCondition = SoftLockingCondition(SoftLockingType.LOCKED_ONLY))
            val resultsLockedOnly = vaultService.queryBy<ContractState>(criteriaLockedOnly)
            assertThat(resultsLockedOnly.states).hasSize(6)

            // soft locked states by single lock id
            val criteriaByLockId = VaultQueryCriteria(softLockingCondition = SoftLockingCondition(SoftLockingType.SPECIFIED, listOf(lockId1)))
            val resultsByLockId = vaultService.queryBy<ContractState>(criteriaByLockId)
            assertThat(resultsByLockId.states).hasSize(2)

            // soft locked states by multiple lock ids
            val criteriaByLockIds = VaultQueryCriteria(softLockingCondition = SoftLockingCondition(SoftLockingType.SPECIFIED, listOf(lockId1, lockId2)))
            val resultsByLockIds = vaultService.queryBy<ContractState>(criteriaByLockIds)
            assertThat(resultsByLockIds.states).hasSize(3)

            // unlocked and locked by `lockId2`
            val criteriaUnlockedAndByLockId = VaultQueryCriteria(softLockingCondition = SoftLockingCondition(SoftLockingType.UNLOCKED_AND_SPECIFIED, listOf(lockId2)))
            val resultsUnlockedAndByLockIds = vaultService.queryBy<ContractState>(criteriaUnlockedAndByLockId)
            assertThat(resultsUnlockedAndByLockIds.states).hasSize(5)

            // missing lockId
            expectedEx.expect(IllegalArgumentException::class.java)
            expectedEx.expectMessage("Must specify one or more lockIds")
            val criteriaMissingLockId = VaultQueryCriteria(softLockingCondition = SoftLockingCondition(SoftLockingType.UNLOCKED_AND_SPECIFIED))
            vaultService.queryBy<ContractState>(criteriaMissingLockId)
        }
    }

    @Test
    fun `logical operator EQUAL`() {
        database.transaction {
            listOf(USD, GBP, CHF).forEach {
                vaultFiller.fillWithSomeTestCash(AMOUNT(100, it), notaryServices, 1, DUMMY_CASH_ISSUER)
            }
            val logicalExpression = builder { CashSchemaV1.PersistentCashState::currency.equal(GBP.currencyCode) }
            val criteria = VaultCustomQueryCriteria(logicalExpression)
            val results = vaultService.queryBy<Cash.State>(criteria)
            assertThat(results.states).hasSize(1)
        }
    }

    @Test
    fun `logical operator NOT EQUAL`() {
        database.transaction {
            listOf(USD, GBP, CHF).forEach {
                vaultFiller.fillWithSomeTestCash(AMOUNT(100, it), notaryServices, 1, DUMMY_CASH_ISSUER)
            }
            val logicalExpression = builder { CashSchemaV1.PersistentCashState::currency.notEqual(GBP.currencyCode) }
            val criteria = VaultCustomQueryCriteria(logicalExpression)
            val results = vaultService.queryBy<Cash.State>(criteria)
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `logical operator GREATER_THAN`() {
        database.transaction {
            listOf(1.DOLLARS, 10.POUNDS, 100.SWISS_FRANCS).forEach {
                vaultFiller.fillWithSomeTestCash(it, notaryServices, 1, DUMMY_CASH_ISSUER)
            }
            val logicalExpression = builder { CashSchemaV1.PersistentCashState::pennies.greaterThan(1000L) }
            val criteria = VaultCustomQueryCriteria(logicalExpression)
            val results = vaultService.queryBy<Cash.State>(criteria)
            assertThat(results.states).hasSize(1)
        }
    }

    @Test
    fun `logical operator GREATER_THAN_OR_EQUAL`() {
        database.transaction {
            listOf(1.DOLLARS, 10.POUNDS, 100.SWISS_FRANCS).forEach {
                vaultFiller.fillWithSomeTestCash(it, notaryServices, 1, DUMMY_CASH_ISSUER)
            }
            val logicalExpression = builder { CashSchemaV1.PersistentCashState::pennies.greaterThanOrEqual(1000L) }
            val criteria = VaultCustomQueryCriteria(logicalExpression)
            val results = vaultService.queryBy<Cash.State>(criteria)
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `logical operator LESS_THAN`() {
        database.transaction {
            listOf(1.DOLLARS, 10.POUNDS, 100.SWISS_FRANCS).forEach {
                vaultFiller.fillWithSomeTestCash(it, notaryServices, 1, DUMMY_CASH_ISSUER)
            }
            val logicalExpression = builder { CashSchemaV1.PersistentCashState::pennies.lessThan(1000L) }
            val criteria = VaultCustomQueryCriteria(logicalExpression)
            val results = vaultService.queryBy<Cash.State>(criteria)
            assertThat(results.states).hasSize(1)
        }
    }

    @Test
    fun `logical operator LESS_THAN_OR_EQUAL`() {
        database.transaction {
            listOf(1.DOLLARS, 10.POUNDS, 100.SWISS_FRANCS).forEach {
                vaultFiller.fillWithSomeTestCash(it, notaryServices, 1, DUMMY_CASH_ISSUER)
            }
            val logicalExpression = builder { CashSchemaV1.PersistentCashState::pennies.lessThanOrEqual(1000L) }
            val criteria = VaultCustomQueryCriteria(logicalExpression)
            val results = vaultService.queryBy<Cash.State>(criteria)
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `logical operator BETWEEN`() {
        database.transaction {
            listOf(1.DOLLARS, 10.POUNDS, 100.SWISS_FRANCS).forEach {
                vaultFiller.fillWithSomeTestCash(it, notaryServices, 1, DUMMY_CASH_ISSUER)
            }
            val logicalExpression = builder { CashSchemaV1.PersistentCashState::pennies.between(500L, 1500L) }
            val criteria = VaultCustomQueryCriteria(logicalExpression)
            val results = vaultService.queryBy<Cash.State>(criteria)
            assertThat(results.states).hasSize(1)
        }
    }

    @Test
    fun `logical operator IN`() {
        database.transaction {
            listOf(USD, GBP, CHF).forEach {
                vaultFiller.fillWithSomeTestCash(AMOUNT(100, it), notaryServices, 1, DUMMY_CASH_ISSUER)
            }
            val currencies = listOf(CHF.currencyCode, GBP.currencyCode)
            val logicalExpression = builder { CashSchemaV1.PersistentCashState::currency.`in`(currencies) }
            val criteria = VaultCustomQueryCriteria(logicalExpression)
            val results = vaultService.queryBy<Cash.State>(criteria)
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `logical operator NOT IN`() {
        database.transaction {
            listOf(USD, GBP, CHF).forEach {
                vaultFiller.fillWithSomeTestCash(AMOUNT(100, it), notaryServices, 1, DUMMY_CASH_ISSUER)
            }
            val currencies = listOf(CHF.currencyCode, GBP.currencyCode)
            val logicalExpression = builder { CashSchemaV1.PersistentCashState::currency.notIn(currencies) }
            val criteria = VaultCustomQueryCriteria(logicalExpression)
            val results = vaultService.queryBy<Cash.State>(criteria)
            assertThat(results.states).hasSize(1)
        }
    }

    @Test
    fun `logical operator LIKE`() {
        database.transaction {
            listOf(USD, GBP, CHF).forEach {
                vaultFiller.fillWithSomeTestCash(AMOUNT(100, it), notaryServices, 1, DUMMY_CASH_ISSUER)
            }
            val logicalExpression = builder { CashSchemaV1.PersistentCashState::currency.like("%BP") }  // GPB
            val criteria = VaultCustomQueryCriteria(logicalExpression)
            val results = vaultService.queryBy<Cash.State>(criteria)
            assertThat(results.states).hasSize(1)
        }
    }

    @Test
    fun `logical operator NOT LIKE`() {
        database.transaction {
            listOf(USD, GBP, CHF).forEach {
                vaultFiller.fillWithSomeTestCash(AMOUNT(100, it), notaryServices, 1, DUMMY_CASH_ISSUER)
            }
            val logicalExpression = builder { CashSchemaV1.PersistentCashState::currency.notLike("%BP") }  // GPB
            val criteria = VaultCustomQueryCriteria(logicalExpression)
            val results = vaultService.queryBy<Cash.State>(criteria)
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `logical operator IS_NULL`() {
        database.transaction {
            listOf(USD, GBP, CHF).forEach {
                vaultFiller.fillWithSomeTestCash(AMOUNT(100, it), notaryServices, 1, DUMMY_CASH_ISSUER)
            }
            val logicalExpression = builder { CashSchemaV1.PersistentCashState::issuerPartyHash.isNull() }
            val criteria = VaultCustomQueryCriteria(logicalExpression)
            val results = vaultService.queryBy<Cash.State>(criteria)
            assertThat(results.states).hasSize(0)
        }
    }

    @Test
    fun `logical operator NOT_NULL`() {
        database.transaction {
            listOf(USD, GBP, CHF).forEach {
                vaultFiller.fillWithSomeTestCash(AMOUNT(100, it), notaryServices, 1, DUMMY_CASH_ISSUER)
            }
            val logicalExpression = builder { CashSchemaV1.PersistentCashState::issuerPartyHash.notNull() }
            val criteria = VaultCustomQueryCriteria(logicalExpression)
            val results = vaultService.queryBy<Cash.State>(criteria)
            assertThat(results.states).hasSize(3)
        }
    }

    @Test
    fun `aggregate functions without group clause`() {
        database.transaction {
            listOf(100.DOLLARS, 200.DOLLARS, 300.DOLLARS, 400.POUNDS, 500.SWISS_FRANCS).zip(1..5).forEach { (howMuch, states) ->
                vaultFiller.fillWithSomeTestCash(howMuch, notaryServices, states, DUMMY_CASH_ISSUER)
            }
            // DOCSTART VaultQueryExample21
            val sum = builder { CashSchemaV1.PersistentCashState::pennies.sum() }
            val sumCriteria = VaultCustomQueryCriteria(sum)

            val count = builder { CashSchemaV1.PersistentCashState::pennies.count() }
            val countCriteria = VaultCustomQueryCriteria(count)

            val max = builder { CashSchemaV1.PersistentCashState::pennies.max() }
            val maxCriteria = VaultCustomQueryCriteria(max)

            val min = builder { CashSchemaV1.PersistentCashState::pennies.min() }
            val minCriteria = VaultCustomQueryCriteria(min)

            val avg = builder { CashSchemaV1.PersistentCashState::pennies.avg() }
            val avgCriteria = VaultCustomQueryCriteria(avg)

            val results = vaultService.queryBy<FungibleAsset<*>>(sumCriteria
                    .and(countCriteria)
                    .and(maxCriteria)
                    .and(minCriteria)
                    .and(avgCriteria))
            // DOCEND VaultQueryExample21

            assertThat(results.otherResults).hasSize(5)
            assertThat(results.otherResults[0]).isEqualTo(150000L)
            assertThat(results.otherResults[1]).isEqualTo(15L)
            assertThat(results.otherResults[2]).isEqualTo(11298L)
            assertThat(results.otherResults[3]).isEqualTo(8702L)
            assertThat(results.otherResults[4]).isEqualTo(10000.0)
        }
    }

    @Test
    fun `aggregate functions with single group clause`() {
        database.transaction {
            listOf(100.DOLLARS, 200.DOLLARS, 300.DOLLARS, 400.POUNDS, 500.SWISS_FRANCS).zip(1..5).forEach { (howMuch, states) ->
                vaultFiller.fillWithSomeTestCash(howMuch, notaryServices, states, DUMMY_CASH_ISSUER)
            }
            // DOCSTART VaultQueryExample22
            val sum = builder { CashSchemaV1.PersistentCashState::pennies.sum(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency)) }
            val sumCriteria = VaultCustomQueryCriteria(sum)

            val max = builder { CashSchemaV1.PersistentCashState::pennies.max(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency)) }
            val maxCriteria = VaultCustomQueryCriteria(max)

            val min = builder { CashSchemaV1.PersistentCashState::pennies.min(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency)) }
            val minCriteria = VaultCustomQueryCriteria(min)

            val avg = builder { CashSchemaV1.PersistentCashState::pennies.avg(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency)) }
            val avgCriteria = VaultCustomQueryCriteria(avg)

            val results = vaultService.queryBy<FungibleAsset<*>>(sumCriteria
                    .and(maxCriteria)
                    .and(minCriteria)
                    .and(avgCriteria))
            // DOCEND VaultQueryExample22

            assertThat(results.otherResults).hasSize(15)
            // the order of rows not guaranteed, a row has format 'NUM, NUM, NUM, CURRENCY_CODE'
            val actualRows = mapOf(results.otherResults[4] as String to results.otherResults.subList(0,4),
                    results.otherResults[9] as String to results.otherResults.subList(5,9),
                    results.otherResults[14] as String to results.otherResults.subList(10,14))

            val expectedRows = mapOf("CHF" to listOf(50000L, 10274L, 9481L, 10000.0),
                    "GBP" to listOf(40000L, 10343L, 9351L, 10000.0),
                    "USD" to listOf(60000L, 11298L, 8702L, 10000.0))

            assertThat(expectedRows["CHF"]).isEqualTo(actualRows["CHF"])
            assertThat(expectedRows["GBP"]).isEqualTo(actualRows["GBP"])
            assertThat(expectedRows["USD"]).isEqualTo(actualRows["USD"])
        }
    }

    @Test
    fun `aggregate functions with single group clause desc first column`() {
        database.transaction {
            listOf(100.DOLLARS, 200.DOLLARS, 300.DOLLARS, 400.POUNDS, 500.SWISS_FRANCS).zip(1..5).forEach { (howMuch, states) ->
                vaultFiller.fillWithSomeTestCash(howMuch, notaryServices, states, DUMMY_CASH_ISSUER)
            }
            val sum = builder { CashSchemaV1.PersistentCashState::pennies.sum(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency), orderBy = Sort.Direction.DESC) }
            val max = builder { CashSchemaV1.PersistentCashState::pennies.max(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency)) }
            val min = builder { CashSchemaV1.PersistentCashState::pennies.min(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency)) }

            val results = vaultService.queryBy<FungibleAsset<*>>(VaultCustomQueryCriteria(sum)
                    .and(VaultCustomQueryCriteria(max))
                    .and(VaultCustomQueryCriteria(min)))

            assertThat(results.otherResults).hasSize(12)

            assertThat(results.otherResults.subList(0,4)).isEqualTo(listOf(60000L, 11298L, 8702L, "USD"))
            assertThat(results.otherResults.subList(4,8)).isEqualTo(listOf(50000L, 10274L, 9481L, "CHF"))
            assertThat(results.otherResults.subList(8,12)).isEqualTo(listOf(40000L, 10343L, 9351L, "GBP"))
        }
    }

    @Test
    fun `aggregate functions with single group clause desc mid column`() {
        database.transaction {
            listOf(100.DOLLARS, 200.DOLLARS, 300.DOLLARS, 400.POUNDS, 500.SWISS_FRANCS).zip(1..5).forEach { (howMuch, states) ->
                vaultFiller.fillWithSomeTestCash(howMuch, notaryServices, states, DUMMY_CASH_ISSUER)
            }
            val sum = builder { CashSchemaV1.PersistentCashState::pennies.sum(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency)) }
            val max = builder { CashSchemaV1.PersistentCashState::pennies.max(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency), orderBy = Sort.Direction.DESC) }
            val min = builder { CashSchemaV1.PersistentCashState::pennies.min(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency)) }

            val results = vaultService.queryBy<FungibleAsset<*>>(VaultCustomQueryCriteria(sum)
                    .and(VaultCustomQueryCriteria(max))
                    .and(VaultCustomQueryCriteria(min)))

            assertThat(results.otherResults).hasSize(12)

            assertThat(results.otherResults.subList(0,4)).isEqualTo(listOf(60000L, 11298L, 8702L, "USD"))
            assertThat(results.otherResults.subList(4,8)).isEqualTo(listOf(40000L, 10343L, 9351L, "GBP"))
            assertThat(results.otherResults.subList(8,12)).isEqualTo(listOf(50000L, 10274L, 9481L, "CHF"))
        }
    }

    @Test
    fun `aggregate functions with single group clause desc last column`() {
        database.transaction {
            listOf(100.DOLLARS, 200.DOLLARS, 300.DOLLARS, 400.POUNDS, 500.SWISS_FRANCS).zip(1..5).forEach { (howMuch, states) ->
                vaultFiller.fillWithSomeTestCash(howMuch, notaryServices, states, DUMMY_CASH_ISSUER)
            }
            val sum = builder { CashSchemaV1.PersistentCashState::pennies.sum(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency)) }
            val max = builder { CashSchemaV1.PersistentCashState::pennies.max(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency)) }
            val min = builder { CashSchemaV1.PersistentCashState::pennies.min(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency), orderBy = Sort.Direction.DESC) }

            val results = vaultService.queryBy<FungibleAsset<*>>(VaultCustomQueryCriteria(sum)
                    .and(VaultCustomQueryCriteria(max))
                    .and(VaultCustomQueryCriteria(min)))

            assertThat(results.otherResults).hasSize(12)

            assertThat(results.otherResults.subList(0,4)).isEqualTo(listOf(50000L, 10274L, 9481L, "CHF"))
            assertThat(results.otherResults.subList(4,8)).isEqualTo(listOf(40000L, 10343L, 9351L, "GBP"))
            assertThat(results.otherResults.subList(8,12)).isEqualTo(listOf(60000L, 11298L, 8702L, "USD"))
        }
    }

    @Test
    fun `aggregate functions sum by issuer and currency and sort by aggregate sum`() {
        database.transaction {
            identitySvc.verifyAndRegisterIdentity(BOC_IDENTITY)
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 1, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestCash(200.DOLLARS, notaryServices, 2, BOC.ref(1))
            vaultFiller.fillWithSomeTestCash(300.POUNDS, notaryServices, 3, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestCash(400.POUNDS, notaryServices, 4, BOC.ref(2))
            // DOCSTART VaultQueryExample23
            val sum = builder {
                CashSchemaV1.PersistentCashState::pennies.sum(groupByColumns = listOf(CashSchemaV1.PersistentCashState::issuerPartyHash,
                        CashSchemaV1.PersistentCashState::currency),
                        orderBy = Sort.Direction.DESC)
            }

            val results = vaultService.queryBy<FungibleAsset<*>>(VaultCustomQueryCriteria(sum))
            // DOCEND VaultQueryExample23

            assertThat(results.otherResults).hasSize(12)

            assertThat(results.otherResults[0]).isEqualTo(40000L)
            assertThat(results.otherResults[1]).isEqualTo(BOC_PUBKEY.toStringShort())
            assertThat(results.otherResults[2]).isEqualTo("GBP")
            assertThat(results.otherResults[3]).isEqualTo(30000L)
            assertThat(results.otherResults[4]).isEqualTo(DUMMY_CASH_ISSUER.party.owningKey.toStringShort())
            assertThat(results.otherResults[5]).isEqualTo("GBP")
            assertThat(results.otherResults[6]).isEqualTo(20000L)
            assertThat(results.otherResults[7]).isEqualTo(BOC_PUBKEY.toStringShort())
            assertThat(results.otherResults[8]).isEqualTo("USD")
            assertThat(results.otherResults[9]).isEqualTo(10000L)
            assertThat(results.otherResults[10]).isEqualTo(DUMMY_CASH_ISSUER.party.owningKey.toStringShort())
            assertThat(results.otherResults[11]).isEqualTo("USD")
        }
    }

    @Test
    fun `aggregate functions count by contract type`() {
        database.transaction {
            // create new states
            vaultFillerCashNotary.fillWithSomeTestCash(100.DOLLARS, notaryServices, 10, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestLinearStates(1, "XYZ")
            vaultFiller.fillWithSomeTestLinearStates(2, "JKL")
            vaultFiller.fillWithSomeTestLinearStates(3, "ABC")
            vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))
            // count fungible assets
            val count = builder { VaultSchemaV1.VaultStates::recordedTime.count() }
            val countCriteria = QueryCriteria.VaultCustomQueryCriteria(count)
            val fungibleStateCount = vaultService.queryBy<FungibleAsset<*>>(countCriteria).otherResults.single() as Long
            assertThat(fungibleStateCount).isEqualTo(10L)

            // count linear states
            val linearStateCount = vaultService.queryBy<LinearState>(countCriteria).otherResults.single() as Long
            assertThat(linearStateCount).isEqualTo(9L)

            // count deal states
            val dealStateCount = vaultService.queryBy<DealState>(countCriteria).otherResults.single() as Long
            assertThat(dealStateCount).isEqualTo(3L)
        }
    }

    @Test
    fun `aggregate functions count by contract type and state status`() {
        database.transaction {
            // create new states
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 10, DUMMY_CASH_ISSUER)
            val linearStatesXYZ = vaultFiller.fillWithSomeTestLinearStates(1, "XYZ")
            val linearStatesJKL = vaultFiller.fillWithSomeTestLinearStates(2, "JKL")
            vaultFiller.fillWithSomeTestLinearStates(3, "ABC")
            val dealStates = vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))
            val count = builder { VaultSchemaV1.VaultStates::recordedTime.count() }

            // count fungible assets
            val countCriteria = QueryCriteria.VaultCustomQueryCriteria(count, Vault.StateStatus.ALL)
            val fungibleStateCount = vaultService.queryBy<FungibleAsset<*>>(countCriteria).otherResults.single() as Long
            assertThat(fungibleStateCount).isEqualTo(10L)

            // count linear states
            val linearStateCount = vaultService.queryBy<LinearState>(countCriteria).otherResults.single() as Long
            assertThat(linearStateCount).isEqualTo(9L)

            // count deal states
            val dealStateCount = vaultService.queryBy<DealState>(countCriteria).otherResults.single() as Long
            assertThat(dealStateCount).isEqualTo(3L)

            // consume some states
            vaultFiller.consumeLinearStates(linearStatesXYZ.states.toList())
            vaultFiller.consumeLinearStates(linearStatesJKL.states.toList())
            vaultFiller.consumeDeals(dealStates.states.filter { it.state.data.linearId.externalId == "456" })
            val cashUpdates = consumeCash(50.DOLLARS)
            // UNCONSUMED states (default)

            // count fungible assets
            val countCriteriaUnconsumed = QueryCriteria.VaultCustomQueryCriteria(count, Vault.StateStatus.UNCONSUMED)
            val fungibleStateCountUnconsumed = vaultService.queryBy<FungibleAsset<*>>(countCriteriaUnconsumed).otherResults.single() as Long
            assertThat(fungibleStateCountUnconsumed.toInt()).isEqualTo(10 - cashUpdates.consumed.size + cashUpdates.produced.size)

            // count linear states
            val linearStateCountUnconsumed = vaultService.queryBy<LinearState>(countCriteriaUnconsumed).otherResults.single() as Long
            assertThat(linearStateCountUnconsumed).isEqualTo(5L)

            // count deal states
            val dealStateCountUnconsumed = vaultService.queryBy<DealState>(countCriteriaUnconsumed).otherResults.single() as Long
            assertThat(dealStateCountUnconsumed).isEqualTo(2L)

            // CONSUMED states

            // count fungible assets
            val countCriteriaConsumed = QueryCriteria.VaultCustomQueryCriteria(count, Vault.StateStatus.CONSUMED)
            val fungibleStateCountConsumed = vaultService.queryBy<FungibleAsset<*>>(countCriteriaConsumed).otherResults.single() as Long
            assertThat(fungibleStateCountConsumed.toInt()).isEqualTo(cashUpdates.consumed.size)

            // count linear states
            val linearStateCountConsumed = vaultService.queryBy<LinearState>(countCriteriaConsumed).otherResults.single() as Long
            assertThat(linearStateCountConsumed).isEqualTo(4L)

            // count deal states
            val dealStateCountConsumed = vaultService.queryBy<DealState>(countCriteriaConsumed).otherResults.single() as Long
            assertThat(dealStateCountConsumed).isEqualTo(1L)
        }
    }

    private val TODAY = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC)

    @Test
    fun `unconsumed states recorded between two time intervals`() {
        database.transaction {
            vaultFillerCashNotary.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            // DOCSTART VaultQueryExample6
            val start = TODAY
            val end = TODAY.plus(30, ChronoUnit.DAYS)
            val recordedBetweenExpression = TimeCondition(
                    QueryCriteria.TimeInstantType.RECORDED,
                    ColumnPredicate.Between(start, end))
            val criteria = VaultQueryCriteria(timeCondition = recordedBetweenExpression)
            val results = vaultService.queryBy<ContractState>(criteria)
            // DOCEND VaultQueryExample6
            assertThat(results.states).hasSize(3)

            // Future
            val startFuture = TODAY.plus(1, ChronoUnit.DAYS)
            val recordedBetweenExpressionFuture = TimeCondition(
                    QueryCriteria.TimeInstantType.RECORDED, ColumnPredicate.Between(startFuture, end))
            val criteriaFuture = VaultQueryCriteria(timeCondition = recordedBetweenExpressionFuture)
            assertThat(vaultService.queryBy<ContractState>(criteriaFuture).states).isEmpty()
        }
    }

    @Test
    fun `states consumed after time`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestLinearStates(10)
            vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))
            this.session.flush()
            consumeCash(100.DOLLARS)
            val asOfDateTime = TODAY
            val consumedAfterExpression = TimeCondition(
                    QueryCriteria.TimeInstantType.CONSUMED, ColumnPredicate.BinaryComparison(BinaryComparisonOperator.GREATER_THAN_OR_EQUAL, asOfDateTime))
            val criteria = VaultQueryCriteria(status = Vault.StateStatus.CONSUMED,
                    timeCondition = consumedAfterExpression)
            val results = vaultService.queryBy<ContractState>(criteria)

            assertThat(results.states).hasSize(3)
        }
    }

    // pagination: first page
    @Test
    fun `all states with paging specification - first page`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 100, DUMMY_CASH_ISSUER)
            // DOCSTART VaultQueryExample7
            val pagingSpec = PageSpecification(DEFAULT_PAGE_NUM, 10)
            val criteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            val results = vaultService.queryBy<ContractState>(criteria, paging = pagingSpec)
            // DOCEND VaultQueryExample7
            assertThat(results.states).hasSize(10)
            assertThat(results.totalStatesAvailable).isEqualTo(100)
        }
    }

    // pagination: last page
    @Test
    fun `all states with paging specification  - last`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(95.DOLLARS, notaryServices, 95, DUMMY_CASH_ISSUER)
            // Last page implies we need to perform a row count for the Query first,
            // and then re-query for a given offset defined by (count - pageSize)
            val pagingSpec = PageSpecification(10, 10)

            val criteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            val results = vaultService.queryBy<ContractState>(criteria, paging = pagingSpec)
            assertThat(results.states).hasSize(5) // should retrieve states 90..94
            assertThat(results.totalStatesAvailable).isEqualTo(95)
        }
    }

    // pagination: invalid page number
    @Test
    fun `invalid page number`() {
        expectedEx.expect(VaultQueryException::class.java)
        expectedEx.expectMessage("Page specification: invalid page number")

        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 100, DUMMY_CASH_ISSUER)
            val pagingSpec = PageSpecification(0, 10)

            val criteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            vaultService.queryBy<ContractState>(criteria, paging = pagingSpec)
        }
    }

    // pagination: invalid page size
    @Test
    fun `invalid page size`() {
        expectedEx.expect(VaultQueryException::class.java)
        expectedEx.expectMessage("Page specification: invalid page size")

        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 100, DUMMY_CASH_ISSUER)
            @Suppress("EXPECTED_CONDITION")
            val pagingSpec = PageSpecification(DEFAULT_PAGE_NUM, @Suppress("INTEGER_OVERFLOW") MAX_PAGE_SIZE + 1)  // overflow = -2147483648
            val criteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            vaultService.queryBy<ContractState>(criteria, paging = pagingSpec)
        }
    }

    // pagination not specified but more than DEFAULT_PAGE_SIZE results available (fail-fast test)
    @Test
    fun `pagination not specified but more than default results available`() {
        expectedEx.expect(VaultQueryException::class.java)
        expectedEx.expectMessage("Please specify a `PageSpecification`")

        database.transaction {
            vaultFiller.fillWithSomeTestCash(201.DOLLARS, notaryServices, 201, DUMMY_CASH_ISSUER)
            val criteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            vaultService.queryBy<ContractState>(criteria)
        }
    }

    // example of querying states with paging using totalStatesAvailable
    private fun queryStatesWithPaging(vaultService: VaultService, pageSize: Int): List<StateAndRef<ContractState>> {
        // DOCSTART VaultQueryExample24
        var pageNumber = DEFAULT_PAGE_NUM
        val states = mutableListOf<StateAndRef<ContractState>>()
        do {
            val pageSpec = PageSpecification(pageNumber = pageNumber, pageSize = pageSize)
            val results = vaultService.queryBy<ContractState>(VaultQueryCriteria(), pageSpec)
            states.addAll(results.states)
            pageNumber++
        } while ((pageSpec.pageSize * (pageNumber - 1)) <= results.totalStatesAvailable)
        // DOCEND VaultQueryExample24
        return states.toList()
    }

    // test paging query example works
    @Test
    fun `test example of querying states with paging works correctly`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(25.DOLLARS, notaryServices, 4, DUMMY_CASH_ISSUER)
            assertThat(queryStatesWithPaging(vaultService, 5).count()).isEqualTo(4)
            vaultFiller.fillWithSomeTestCash(25.DOLLARS, notaryServices, 1, DUMMY_CASH_ISSUER)
            assertThat(queryStatesWithPaging(vaultService, 5).count()).isEqualTo(5)
            vaultFiller.fillWithSomeTestCash(25.DOLLARS, notaryServices, 1, DUMMY_CASH_ISSUER)
            assertThat(queryStatesWithPaging(vaultService, 5).count()).isEqualTo(6)
        }
    }

    // test paging with aggregate function and group by clause
    @Test
    fun `test paging with aggregate function and group by clause`() {
        database.transaction {
            (0..200).forEach {
                vaultFiller.fillWithSomeTestLinearStates(1, linearNumber = it.toLong(), linearString = it.toString())
            }
            val max = builder { DummyLinearStateSchemaV1.PersistentDummyLinearState::linearTimestamp.max(
                    groupByColumns = listOf(DummyLinearStateSchemaV1.PersistentDummyLinearState::linearNumber)
                )
            }
            val maxCriteria = VaultCustomQueryCriteria(max)
            val pageSpec = PageSpecification(DEFAULT_PAGE_NUM, MAX_PAGE_SIZE)

            val results = vaultService.queryBy<DummyLinearContract.State>(maxCriteria, paging = pageSpec)
            println("Total states available: ${results.totalStatesAvailable}")
            results.otherResults.forEachIndexed { index, any ->
                println("$index : $any")
            }
            assertThat(results.otherResults.size).isEqualTo(402)
            assertThat(results.otherResults.last()).isEqualTo(200L)
        }
    }

    // sorting
    @Test
    fun `sorting - all states sorted by contract type, state status, consumed time`() {
        database.transaction {
            setUpDb(database)
            val sortCol1 = Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.CONTRACT_STATE_TYPE), Sort.Direction.DESC)
            val sortCol2 = Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.STATE_STATUS), Sort.Direction.ASC)
            val sortCol3 = Sort.SortColumn(SortAttribute.Standard(Sort.VaultStateAttribute.CONSUMED_TIME), Sort.Direction.DESC)
            val sorting = Sort(setOf(sortCol1, sortCol2, sortCol3))
            val result = vaultService.queryBy<ContractState>(VaultQueryCriteria(status = Vault.StateStatus.ALL), sorting = sorting)

            val states = result.states
            val metadata = result.statesMetadata

            assertThat(states).hasSize(20)
            assertThat(metadata.first().contractStateClassName).isEqualTo("$DUMMY_LINEAR_CONTRACT_PROGRAM_ID\$State")
            assertThat(metadata.first().status).isEqualTo(Vault.StateStatus.UNCONSUMED) // 0 = UNCONSUMED
            assertThat(metadata.last().contractStateClassName).isEqualTo("net.corda.finance.contracts.asset.Cash\$State")
            assertThat(metadata.last().status).isEqualTo(Vault.StateStatus.CONSUMED)    // 1 = CONSUMED
        }
    }

    @Test
    fun `unconsumed fungible assets`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestCommodity(Amount(100, Commodity.getInstance("FCOJ")!!), notaryServices, DUMMY_OBLIGATION_ISSUER.ref(1))
            vaultFiller.fillWithSomeTestLinearStates(10)

            val results = vaultService.queryBy<FungibleAsset<*>>()
            assertThat(results.states).hasSize(4)
        }
    }

    @Test
    fun `consumed fungible assets`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            this.session.flush()

            consumeCash(50.DOLLARS)
            vaultFiller.fillWithSomeTestCommodity(Amount(100, Commodity.getInstance("FCOJ")!!), notaryServices, DUMMY_OBLIGATION_ISSUER.ref(1))
            vaultFiller.fillWithSomeTestLinearStates(10)
            val criteria = VaultQueryCriteria(status = Vault.StateStatus.CONSUMED)
            val results = vaultService.queryBy<FungibleAsset<*>>(criteria)
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `unconsumed cash fungible assets`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestLinearStates(10)
            val results = vaultService.queryBy<Cash.State>()
            assertThat(results.states).hasSize(3)
        }
    }

    @Test
    fun `unconsumed cash fungible assets after spending`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            this.session.flush()

            consumeCash(50.DOLLARS)
            // should now have x2 CONSUMED + x2 UNCONSUMED (one spent + one change)
            val results = vaultService.queryBy<Cash.State>(FungibleAssetQueryCriteria())
            assertThat(results.statesMetadata).hasSize(2)
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `consumed cash fungible assets`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            this.session.flush()

            consumeCash(50.DOLLARS)
            val linearStates = vaultFiller.fillWithSomeTestLinearStates(10)
            vaultFiller.consumeLinearStates(linearStates.states.toList())
            val criteria = VaultQueryCriteria(status = Vault.StateStatus.CONSUMED)
            val results = vaultService.queryBy<Cash.State>(criteria)
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `unconsumed linear heads`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestLinearStates(10)
            vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))
            val results = vaultService.queryBy<LinearState>()
            assertThat(results.states).hasSize(13)
        }
    }

    @Test
    fun `consumed linear heads`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            val linearStates = vaultFiller.fillWithSomeTestLinearStates(2, "TEST") // create 2 states with same externalId
            vaultFiller.fillWithSomeTestLinearStates(8)
            val dealStates = vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))
            vaultFiller.consumeLinearStates(linearStates.states.toList())
            vaultFiller.consumeDeals(dealStates.states.filter { it.state.data.linearId.externalId == "456" })
            consumeCash(50.DOLLARS)
            val criteria = VaultQueryCriteria(status = Vault.StateStatus.CONSUMED)
            val results = vaultService.queryBy<LinearState>(criteria)
            assertThat(results.states).hasSize(3)
        }
    }

    /** LinearState tests */

    @Test
    fun `unconsumed linear heads for linearId without external Id`() {
        database.transaction {
            val issuedStates = vaultFiller.fillWithSomeTestLinearStates(10)
            // DOCSTART VaultQueryExample8
            val linearIds = issuedStates.states.map { it.state.data.linearId }.toList()
            val criteria = LinearStateQueryCriteria(linearId = listOf(linearIds.first(), linearIds.last()))
            val results = vaultService.queryBy<LinearState>(criteria)
            // DOCEND VaultQueryExample8
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `unconsumed linear heads by linearId`() {
        database.transaction {
            val linearState1 = vaultFiller.fillWithSomeTestLinearStates(1, "ID1")
            vaultFiller.fillWithSomeTestLinearStates(1, "ID2")
            val linearState3 = vaultFiller.fillWithSomeTestLinearStates(1, "ID3")
            val linearIds = listOf(linearState1.states.first().state.data.linearId, linearState3.states.first().state.data.linearId)
            val criteria = LinearStateQueryCriteria(linearId = linearIds)
            val results = vaultService.queryBy<LinearState>(criteria)
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `unconsumed linear heads for linearId by external Id`() {
        database.transaction {
            val linearState1 = vaultFiller.fillWithSomeTestLinearStates(1, "ID1")
            vaultFiller.fillWithSomeTestLinearStates(1, "ID2")
            val linearState3 = vaultFiller.fillWithSomeTestLinearStates(1, "ID3")
            val externalIds = listOf(linearState1.states.first().state.data.linearId.externalId!!, linearState3.states.first().state.data.linearId.externalId!!)
            val criteria = LinearStateQueryCriteria(externalId = externalIds)
            val results = vaultService.queryBy<LinearState>(criteria)
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `all linear states for a given linear id`() {
        database.transaction {
            val txns = vaultFiller.fillWithSomeTestLinearStates(1, "TEST")
            val linearState = txns.states.first()
            repeat(3) {
                vaultFiller.evolveLinearState(linearState)  // consume current and produce new state reference
            }
            val linearId = linearState.state.data.linearId

            // should now have 1 UNCONSUMED & 3 CONSUMED state refs for Linear State with "TEST"
            // DOCSTART VaultQueryExample9
            val linearStateCriteria = LinearStateQueryCriteria(linearId = listOf(linearId), status = Vault.StateStatus.ALL)
            val vaultCriteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            val results = vaultService.queryBy<LinearState>(linearStateCriteria and vaultCriteria)
            // DOCEND VaultQueryExample9
            assertThat(results.states).hasSize(4)
        }
    }

    @Test
    fun `all linear states for a given id sorted by uuid`() {
        database.transaction {
            val txns = vaultFiller.fillWithSomeTestLinearStates(2, "TEST")
            val linearStates = txns.states.toList()
            repeat(3) {
                vaultFiller.evolveLinearStates(linearStates)  // consume current and produce new state reference
            }
            // should now have 1 UNCONSUMED & 3 CONSUMED state refs for Linear State with "TEST"
            val linearStateCriteria = LinearStateQueryCriteria(uuid = linearStates.map { it.state.data.linearId.id }, status = Vault.StateStatus.ALL)
            val vaultCriteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
            val sorting = Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.LinearStateAttribute.UUID), Sort.Direction.DESC)))

            val results = vaultService.queryBy<LinearState>(linearStateCriteria.and(vaultCriteria), sorting = sorting)
            results.states.forEach { println("${it.state.data.linearId.id}") }
            assertThat(results.states).hasSize(8)
        }
    }

    @Test
    fun `unconsumed linear states sorted by external id`() {
        database.transaction {
            vaultFiller.fillWithSomeTestLinearStates(1, externalId = "111")
            vaultFiller.fillWithSomeTestLinearStates(2, externalId = "222")
            vaultFiller.fillWithSomeTestLinearStates(3, externalId = "333")
            val vaultCriteria = VaultQueryCriteria()
            val sorting = Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.LinearStateAttribute.EXTERNAL_ID), Sort.Direction.DESC)))

            val results = vaultService.queryBy<DummyLinearContract.State>((vaultCriteria), sorting = sorting)
            assertThat(results.states).hasSize(6)
        }
    }

    @Test
    fun `unconsumed deal states sorted`() {
        database.transaction {
            val linearStates = vaultFiller.fillWithSomeTestLinearStates(10)
            vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))
            val uid = linearStates.states.first().state.data.linearId.id

            val linearStateCriteria = LinearStateQueryCriteria(uuid = listOf(uid))
            val dealStateCriteria = LinearStateQueryCriteria(externalId = listOf("123", "456", "789"))
            val compositeCriteria = linearStateCriteria or dealStateCriteria

            val sorting = Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.LinearStateAttribute.EXTERNAL_ID), Sort.Direction.DESC)))

            val results = vaultService.queryBy<LinearState>(compositeCriteria, sorting = sorting)
            assertThat(results.statesMetadata).hasSize(4)
            assertThat(results.states).hasSize(4)
        }
    }

    @Test
    fun `unconsumed linear states sorted by custom attribute`() {
        database.transaction {
            vaultFiller.fillWithSomeTestLinearStates(1, linearString = "111")
            vaultFiller.fillWithSomeTestLinearStates(2, linearString = "222")
            vaultFiller.fillWithSomeTestLinearStates(3, linearString = "333")
            val vaultCriteria = VaultQueryCriteria()
            val sorting = Sort(setOf(Sort.SortColumn(SortAttribute.Custom(DummyLinearStateSchemaV1.PersistentDummyLinearState::class.java, "linearString"), Sort.Direction.DESC)))

            val results = vaultService.queryBy<DummyLinearContract.State>((vaultCriteria), sorting = sorting)
            results.states.forEach { println(it.state.data.linearString) }
            assertThat(results.states).hasSize(6)
        }
    }

    @Test
    fun `return consumed linear states for a given linear id`() {
        database.transaction {
            val txns = vaultFiller.fillWithSomeTestLinearStates(1, "TEST")
            val linearState = txns.states.first()
            val linearState2 = vaultFiller.evolveLinearState(linearState)  // consume current and produce new state reference
            val linearState3 = vaultFiller.evolveLinearState(linearState2)  // consume current and produce new state reference
            vaultFiller.evolveLinearState(linearState3)  // consume current and produce new state reference
            // should now have 1 UNCONSUMED & 3 CONSUMED state refs for Linear State with "TEST"
            val linearStateCriteria = LinearStateQueryCriteria(linearId = txns.states.map { it.state.data.linearId }, status = Vault.StateStatus.CONSUMED)
            val vaultCriteria = VaultQueryCriteria(status = Vault.StateStatus.CONSUMED)
            val sorting = Sort(setOf(Sort.SortColumn(SortAttribute.Standard(Sort.LinearStateAttribute.UUID), Sort.Direction.DESC)))
            val results = vaultService.queryBy<LinearState>(linearStateCriteria.and(vaultCriteria), sorting = sorting)
            assertThat(results.states).hasSize(3)
        }
    }

    /**
     *  Deal Contract state to be removed as is duplicate of LinearState
     */
    @Test
    fun `unconsumed deals`() {
        database.transaction {
            vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))
            val results = vaultService.queryBy<DealState>()
            assertThat(results.states).hasSize(3)
        }
    }

    @Test
    fun `unconsumed deals for ref`() {
        database.transaction {
            vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789"))
            // DOCSTART VaultQueryExample10
            val criteria = LinearStateQueryCriteria(externalId = listOf("456", "789"))
            val results = vaultService.queryBy<DealState>(criteria)
            // DOCEND VaultQueryExample10

            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `latest unconsumed deals for ref`() {
        database.transaction {
            vaultFiller.fillWithSomeTestLinearStates(2, "TEST")
            vaultFiller.fillWithSomeTestDeals(listOf("456"))
            vaultFiller.fillWithSomeTestDeals(listOf("123", "789"))

            val criteria = LinearStateQueryCriteria(externalId = listOf("456"))
            val results = vaultService.queryBy<DealState>(criteria)
            assertThat(results.states).hasSize(1)
        }
    }

    @Test
    fun `latest unconsumed deals with party`() {
        val parties = listOf(MINI_CORP)
        database.transaction {
            vaultFiller.fillWithSomeTestLinearStates(2, "TEST")
            vaultFiller.fillWithSomeTestDeals(listOf("456"), participants = parties)
            vaultFiller.fillWithSomeTestDeals(listOf("123", "789"))
            // DOCSTART VaultQueryExample11
            val criteria = LinearStateQueryCriteria(participants = parties)
            val results = vaultService.queryBy<DealState>(criteria)
            // DOCEND VaultQueryExample11

            assertThat(results.states).hasSize(1)
        }
    }

    /** FungibleAsset tests */

    @Test
    fun `unconsumed fungible assets for specific issuer party and refs`() {
        database.transaction {
            identitySvc.verifyAndRegisterIdentity(BOC_IDENTITY)
            listOf(DUMMY_CASH_ISSUER, BOC.ref(1), BOC.ref(2), BOC.ref(3)).forEach {
                vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 1, it)
            }
            val criteria = FungibleAssetQueryCriteria(issuer = listOf(BOC),
                    issuerRef = listOf(BOC.ref(1).reference, BOC.ref(2).reference))
            val results = vaultService.queryBy<FungibleAsset<*>>(criteria)
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `unconsumed fungible assets for selected issuer parties`() {
        // GBP issuer
        val gbpCashIssuerName = CordaX500Name(organisation = "British Pounds Cash Issuer", locality = "London", country = "GB")
        val gbpCashIssuerServices = MockServices(cordappPackages, gbpCashIssuerName, rigorousMock(), generateKeyPair())
        val gbpCashIssuer = gbpCashIssuerServices.myInfo.singleIdentityAndCert()
        // USD issuer
        val usdCashIssuerName = CordaX500Name(organisation = "US Dollars Cash Issuer", locality = "New York", country = "US")
        val usdCashIssuerServices = MockServices(cordappPackages, usdCashIssuerName, rigorousMock(), generateKeyPair())
        val usdCashIssuer = usdCashIssuerServices.myInfo.singleIdentityAndCert()
        // CHF issuer
        val chfCashIssuerName = CordaX500Name(organisation = "Swiss Francs Cash Issuer", locality = "Zurich", country = "CH")
        val chfCashIssuerServices = MockServices(cordappPackages, chfCashIssuerName, rigorousMock(), generateKeyPair())
        val chfCashIssuer = chfCashIssuerServices.myInfo.singleIdentityAndCert()
        listOf(gbpCashIssuer, usdCashIssuer, chfCashIssuer).forEach { identity ->
            services.identityService.verifyAndRegisterIdentity(identity)
        }
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.POUNDS, gbpCashIssuerServices, 1, gbpCashIssuer.party.ref(1))
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, usdCashIssuerServices, 1, usdCashIssuer.party.ref(1))
            vaultFiller.fillWithSomeTestCash(100.SWISS_FRANCS, chfCashIssuerServices, 1, chfCashIssuer.party.ref(1))
            this.session.flush()

            val criteria = FungibleAssetQueryCriteria(issuer = listOf(gbpCashIssuer.party, usdCashIssuer.party))
            val results = vaultService.queryBy<FungibleAsset<*>>(criteria)
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `unconsumed fungible assets by owner`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 1, BOC.ref(1))
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 1, MEGA_CORP.ref(0), MINI_CORP)

            val criteria = FungibleAssetQueryCriteria(owner = listOf(MEGA_CORP))
            val results = vaultService.queryBy<FungibleAsset<*>>(criteria)
            assertThat(results.states).hasSize(1)   // can only be 1 owner of a node (MEGA_CORP in this MockServices setup)
        }
    }

    @Test
    fun `unconsumed fungible states for owners`() {
        database.transaction {
            vaultFillerCashNotary.fillWithSomeTestCash(100.DOLLARS, notaryServices, 1, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 1, MEGA_CORP.ref(0), MEGA_CORP)
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 1, BOC.ref(0), MINI_CORP)  // irrelevant to this vault

            // DOCSTART VaultQueryExample5.2
            val criteria = FungibleAssetQueryCriteria(owner = listOf(MEGA_CORP, BOC))
            val results = vaultService.queryBy<ContractState>(criteria)
            // DOCEND VaultQueryExample5.2

            assertThat(results.states).hasSize(2)   // can only be 1 owner of a node (MEGA_CORP in this MockServices setup)
        }
    }

    /** Cash Fungible State specific */
    @Test
    fun `unconsumed fungible assets for single currency`() {
        database.transaction {
            vaultFiller.fillWithSomeTestLinearStates(10)
            listOf(USD, GBP, CHF).forEach {
                vaultFiller.fillWithSomeTestCash(AMOUNT(100, it), notaryServices, 3, DUMMY_CASH_ISSUER)
            }
            // DOCSTART VaultQueryExample12
            val ccyIndex = builder { CashSchemaV1.PersistentCashState::currency.equal(USD.currencyCode) }
            val criteria = VaultCustomQueryCriteria(ccyIndex)
            val results = vaultService.queryBy<FungibleAsset<*>>(criteria)
            // DOCEND VaultQueryExample12

            assertThat(results.states).hasSize(3)
        }
    }

    @Test
    fun `unconsumed cash balance for single currency`() {
        database.transaction {
            listOf(100, 200).zip(1..2).forEach { (howMuch, states) ->
                vaultFiller.fillWithSomeTestCash(howMuch.DOLLARS, notaryServices, states, DUMMY_CASH_ISSUER)
            }
            val sum = builder { CashSchemaV1.PersistentCashState::pennies.sum(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency)) }
            val sumCriteria = VaultCustomQueryCriteria(sum)

            val ccyIndex = builder { CashSchemaV1.PersistentCashState::currency.equal(USD.currencyCode) }
            val ccyCriteria = VaultCustomQueryCriteria(ccyIndex)

            val results = vaultService.queryBy<FungibleAsset<*>>(sumCriteria.and(ccyCriteria))

            assertThat(results.otherResults).hasSize(2)
            assertThat(results.otherResults[0]).isEqualTo(30000L)
            assertThat(results.otherResults[1]).isEqualTo("USD")
        }
    }

    @Test
    fun `unconsumed cash balances for all currencies`() {
        database.transaction {
            listOf(100.DOLLARS, 200.DOLLARS, 300.POUNDS, 400.POUNDS, 500.SWISS_FRANCS, 600.SWISS_FRANCS).zip(1..6).forEach { (howMuch, states) ->
                vaultFiller.fillWithSomeTestCash(howMuch, notaryServices, states, DUMMY_CASH_ISSUER)
            }
            val ccyIndex = builder { CashSchemaV1.PersistentCashState::pennies.sum(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency)) }
            val criteria = VaultCustomQueryCriteria(ccyIndex)
            val results = vaultService.queryBy<FungibleAsset<*>>(criteria)

            assertThat(results.otherResults).hasSize(6)
            // the order of rows not guaranteed
            val actualTotals = mapOf(results.otherResults[1] as String to results.otherResults[0] as Long,
                    results.otherResults[3] as String to results.otherResults[2] as Long,
                    results.otherResults[5] as String to results.otherResults[4] as Long)
            val expectedTotals = mapOf("CHF" to 110000L, "GBP" to 70000L, "USD" to 30000L)
            assertThat(expectedTotals["CHF"]).isEqualTo(actualTotals["CHF"])
            assertThat(expectedTotals["GBP"]).isEqualTo(actualTotals["GBP"])
            assertThat(expectedTotals["USD"]).isEqualTo(actualTotals["USD"])
        }
    }

    @Test
    fun `unconsumed fungible assets for quantity greater than`() {
        database.transaction {
            listOf(10.DOLLARS, 25.POUNDS, 50.POUNDS, 100.SWISS_FRANCS).zip(listOf(3, 1, 1, 3)).forEach { (howMuch, states) ->
                vaultFiller.fillWithSomeTestCash(howMuch, notaryServices, states, DUMMY_CASH_ISSUER)
            }
            // DOCSTART VaultQueryExample13
            val fungibleAssetCriteria = FungibleAssetQueryCriteria(quantity = builder { greaterThan(2500L) })
            val results = vaultService.queryBy<Cash.State>(fungibleAssetCriteria)
            // DOCEND VaultQueryExample13

            assertThat(results.states).hasSize(4)  // POUNDS, SWISS_FRANCS
        }
    }

    @Test
    fun `unconsumed fungible assets for issuer party`() {
        database.transaction {
            identitySvc.verifyAndRegisterIdentity(BOC_IDENTITY)
            listOf(DUMMY_CASH_ISSUER, BOC.ref(1)).forEach {
                vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 1, it)
            }
            // DOCSTART VaultQueryExample14
            val criteria = FungibleAssetQueryCriteria(issuer = listOf(BOC))
            val results = vaultService.queryBy<FungibleAsset<*>>(criteria)
            // DOCEND VaultQueryExample14

            assertThat(results.states).hasSize(1)
        }
    }

    @Test
    fun `unconsumed fungible assets for single currency and quantity greater than`() {
        database.transaction {
            listOf(100.DOLLARS, 100.POUNDS, 50.POUNDS, 100.SWISS_FRANCS).forEach {
                vaultFiller.fillWithSomeTestCash(it, notaryServices, 1, DUMMY_CASH_ISSUER)
            }
            val ccyIndex = builder { CashSchemaV1.PersistentCashState::currency.equal(GBP.currencyCode) }
            val customCriteria = VaultCustomQueryCriteria(ccyIndex)
            val fungibleAssetCriteria = FungibleAssetQueryCriteria(quantity = builder { greaterThan(5000L) })
            val results = vaultService.queryBy<Cash.State>(fungibleAssetCriteria.and(customCriteria))

            assertThat(results.states).hasSize(1)   // POUNDS > 50
        }
    }

    /** Vault Custom Query tests */

    // specifying Query on Commercial Paper contract state attributes
    @Test
    fun `custom query using JPA - commercial paper schema V1 single attribute`() {
        database.transaction {
            val issuance = MEGA_CORP.ref(1)

            // MegaCorp™ issues $10,000 of commercial paper, to mature in 30 days, owned by itself.
            val faceValue = 10000.DOLLARS `issued by` DUMMY_CASH_ISSUER
            val commercialPaper =
                    CommercialPaper().generateIssue(issuance, faceValue, TEST_TX_TIME + 30.days, DUMMY_NOTARY).let { builder ->
                        builder.setTimeWindow(TEST_TX_TIME, 30.seconds)
                        val stx = services.signInitialTransaction(builder, MEGA_CORP_PUBKEY)
                        notaryServices.addSignature(stx, DUMMY_NOTARY_KEY.public)
                    }

            services.recordTransactions(commercialPaper)

            // MegaCorp™ now issues £10,000 of commercial paper, to mature in 30 days, owned by itself.
            val faceValue2 = 10000.POUNDS `issued by` DUMMY_CASH_ISSUER
            val commercialPaper2 =
                    CommercialPaper().generateIssue(issuance, faceValue2, TEST_TX_TIME + 30.days, DUMMY_NOTARY).let { builder ->
                        builder.setTimeWindow(TEST_TX_TIME, 30.seconds)
                        val stx = services.signInitialTransaction(builder, MEGA_CORP_PUBKEY)
                        notaryServices.addSignature(stx, DUMMY_NOTARY_KEY.public)
                    }
            services.recordTransactions(commercialPaper2)

            val ccyIndex = builder { CommercialPaperSchemaV1.PersistentCommercialPaperState::currency.equal(USD.currencyCode) }
            val criteria1 = QueryCriteria.VaultCustomQueryCriteria(ccyIndex)

            val result = vaultService.queryBy<CommercialPaper.State>(criteria1)

            assertThat(result.states).hasSize(1)
            assertThat(result.statesMetadata).hasSize(1)
        }
    }

    // specifying Query on Commercial Paper contract state attributes
    @Test
    fun `custom query using JPA - commercial paper schema V1 - multiple attributes`() {
        database.transaction {
            val issuance = MEGA_CORP.ref(1)

            // MegaCorp™ issues $10,000 of commercial paper, to mature in 30 days, owned by itself.
            val faceValue = 10000.DOLLARS `issued by` DUMMY_CASH_ISSUER
            val commercialPaper =
                    CommercialPaper().generateIssue(issuance, faceValue, TEST_TX_TIME + 30.days, DUMMY_NOTARY).let { builder ->
                        builder.setTimeWindow(TEST_TX_TIME, 30.seconds)
                        val stx = services.signInitialTransaction(builder, MEGA_CORP_PUBKEY)
                        notaryServices.addSignature(stx, DUMMY_NOTARY_KEY.public)
                    }
            commercialPaper.verifyRequiredSignatures()
            services.recordTransactions(commercialPaper)

            // MegaCorp™ now issues £5,000 of commercial paper, to mature in 30 days, owned by itself.
            val faceValue2 = 5000.POUNDS `issued by` DUMMY_CASH_ISSUER
            val commercialPaper2 =
                    CommercialPaper().generateIssue(issuance, faceValue2, TEST_TX_TIME + 30.days, DUMMY_NOTARY).let { builder ->
                        builder.setTimeWindow(TEST_TX_TIME, 30.seconds)
                        val stx = services.signInitialTransaction(builder, MEGA_CORP_PUBKEY)
                        notaryServices.addSignature(stx, DUMMY_NOTARY_KEY.public)
                    }
            commercialPaper2.verifyRequiredSignatures()
            services.recordTransactions(commercialPaper2)

            val result = builder {

                val ccyIndex = CommercialPaperSchemaV1.PersistentCommercialPaperState::currency.equal(USD.currencyCode)
                val maturityIndex = CommercialPaperSchemaV1.PersistentCommercialPaperState::maturity.greaterThanOrEqual(TEST_TX_TIME + 30.days)
                val faceValueIndex = CommercialPaperSchemaV1.PersistentCommercialPaperState::faceValue.greaterThanOrEqual(10000L)

                val criteria1 = QueryCriteria.VaultCustomQueryCriteria(ccyIndex)
                val criteria2 = QueryCriteria.VaultCustomQueryCriteria(maturityIndex)
                val criteria3 = QueryCriteria.VaultCustomQueryCriteria(faceValueIndex)

                vaultService.queryBy<CommercialPaper.State>(criteria1.and(criteria3).and(criteria2))
            }
            assertThat(result.states).hasSize(1)
            assertThat(result.statesMetadata).hasSize(1)
        }
    }

    /** Chaining together different Query Criteria tests**/

    // specifying Query on Cash contract state attributes
    @Test
    fun `custom - all cash states with amount of currency greater or equal than`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.POUNDS, notaryServices, 1, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 1, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestCash(10.DOLLARS, notaryServices, 1, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestCash(1.DOLLARS, notaryServices, 1, DUMMY_CASH_ISSUER)
            // DOCSTART VaultQueryExample20
            val generalCriteria = VaultQueryCriteria(Vault.StateStatus.ALL)

            val results = builder {
                val currencyIndex = PersistentCashState::currency.equal(USD.currencyCode)
                val quantityIndex = PersistentCashState::pennies.greaterThanOrEqual(10L)

                val customCriteria1 = VaultCustomQueryCriteria(currencyIndex)
                val customCriteria2 = VaultCustomQueryCriteria(quantityIndex)

                val criteria = generalCriteria.and(customCriteria1.and(customCriteria2))
                vaultService.queryBy<Cash.State>(criteria)
            }
            // DOCEND VaultQueryExample20

            assertThat(results.states).hasSize(3)
        }
    }

    // specifying Query on Linear state attributes
    @Test
    fun `unconsumed linear heads for linearId between two timestamps`() {
        val start = Instant.now()
        val end = start.plus(1, ChronoUnit.SECONDS)

        database.transaction {
            vaultFiller.fillWithSomeTestLinearStates(1, "TEST")
            sleep(1000)
            vaultFiller.fillWithSomeTestLinearStates(1, "TEST")
            // 2 unconsumed states with same external ID
            val recordedBetweenExpression = TimeCondition(TimeInstantType.RECORDED, builder { between(start, end) })
            val basicCriteria = VaultQueryCriteria(timeCondition = recordedBetweenExpression)

            val results = vaultService.queryBy<LinearState>(basicCriteria)

            assertThat(results.states).hasSize(1)
        }
    }

    // specifying Query on Linear state attributes
    @Test
    fun `unconsumed linear heads for a given external id`() {
        database.transaction {
            vaultFiller.fillWithSomeTestLinearStates(1, "TEST1")
            vaultFiller.fillWithSomeTestLinearStates(1, "TEST2")
            // 2 unconsumed states with same external ID
            val externalIdCondition = builder { VaultSchemaV1.VaultLinearStates::externalId.equal("TEST2") }
            val externalIdCustomCriteria = VaultCustomQueryCriteria(externalIdCondition)

            val results = vaultService.queryBy<LinearState>(externalIdCustomCriteria)

            assertThat(results.states).hasSize(1)
        }
    }

    // specifying Query on Linear state attributes
    @Test
    fun `unconsumed linear heads for linearId between two timestamps for a given external id`() {
        val start = Instant.now()
        val end = start.plus(1, ChronoUnit.SECONDS)

        database.transaction {
            vaultFiller.fillWithSomeTestLinearStates(1, "TEST1")
            vaultFiller.fillWithSomeTestLinearStates(1, "TEST2")
            sleep(1000)
            vaultFiller.fillWithSomeTestLinearStates(1, "TEST3")
            // 2 unconsumed states with same external ID

            val results = builder {
                val linearIdCondition = VaultSchemaV1.VaultLinearStates::externalId.equal("TEST2")
                val customCriteria = VaultCustomQueryCriteria(linearIdCondition)

                val recordedBetweenExpression = TimeCondition(TimeInstantType.RECORDED, between(start, end))
                val basicCriteria = VaultQueryCriteria(timeCondition = recordedBetweenExpression)

                val criteria = basicCriteria.and(customCriteria)
                vaultService.queryBy<LinearState>(criteria)
            }

            assertThat(results.states).hasSize(1)
        }
    }

    // specifying Query on Linear state attributes
    @Test
    fun `unconsumed linear heads for a given external id or uuid`() {
        database.transaction {
            vaultFiller.fillWithSomeTestLinearStates(1, "TEST1")
            val aState = vaultFiller.fillWithSomeTestLinearStates(1, "TEST2").states
            vaultFiller.consumeLinearStates(aState.toList())
            val uuid = vaultFiller.fillWithSomeTestLinearStates(1, "TEST1").states.first().state.data.linearId.id
            // 2 unconsumed states with same external ID, 1 consumed with different external ID
            val results = builder {
                val externalIdCondition = VaultSchemaV1.VaultLinearStates::externalId.equal("TEST1")
                val externalIdCustomCriteria = VaultCustomQueryCriteria(externalIdCondition)

                val uuidCondition = VaultSchemaV1.VaultLinearStates::uuid.equal(uuid)
                val uuidCustomCriteria = VaultCustomQueryCriteria(uuidCondition)

                val criteria = externalIdCustomCriteria or uuidCustomCriteria
                vaultService.queryBy<LinearState>(criteria)
            }
            assertThat(results.statesMetadata).hasSize(2)
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `unconsumed linear heads for single participant`() {
        database.transaction {
            identitySvc.verifyAndRegisterIdentity(ALICE_IDENTITY)
            vaultFiller.fillWithSomeTestLinearStates(1, "TEST1", listOf(ALICE))
            vaultFiller.fillWithSomeTestLinearStates(1)
            vaultFiller.fillWithSomeTestLinearStates(1, "TEST3")
            val linearStateCriteria = LinearStateQueryCriteria(participants = listOf(ALICE))
            val results = vaultService.queryBy<LinearState>(linearStateCriteria)

            assertThat(results.states).hasSize(1)
            assertThat(results.states[0].state.data.linearId.externalId).isEqualTo("TEST1")
        }
    }

    @Test
    fun `unconsumed linear heads for multiple participants`() {
        database.transaction {
            identitySvc.verifyAndRegisterIdentity(ALICE_IDENTITY)
            identitySvc.verifyAndRegisterIdentity(BOB_IDENTITY)
            identitySvc.verifyAndRegisterIdentity(CHARLIE_IDENTITY)
            vaultFiller.fillWithSomeTestLinearStates(1, "TEST1", listOf(ALICE, BOB, CHARLIE))
            vaultFiller.fillWithSomeTestLinearStates(1)
            vaultFiller.fillWithSomeTestLinearStates(1, "TEST3")
            val linearStateCriteria = LinearStateQueryCriteria(participants = listOf(ALICE, BOB, CHARLIE))
            val results = vaultService.queryBy<LinearState>(linearStateCriteria)

            assertThat(results.states).hasSize(1)
            assertThat(results.states[0].state.data.linearId.externalId).isEqualTo("TEST1")
        }
    }

    @Test
    fun `unconsumed linear heads where external id is null`() {
        database.transaction {
            vaultFiller.fillWithSomeTestLinearStates(1, "TEST1")
            vaultFiller.fillWithSomeTestLinearStates(1)
            vaultFiller.fillWithSomeTestLinearStates(1, "TEST3")
            // 3 unconsumed states (one without an external ID)
            val results = builder {
                val externalIdCondition = VaultSchemaV1.VaultLinearStates::externalId.isNull()
                val externalIdCustomCriteria = VaultCustomQueryCriteria(externalIdCondition)

                vaultService.queryBy<LinearState>(externalIdCustomCriteria)
            }
            assertThat(results.states).hasSize(1)
        }
    }

    @Test
    fun `unconsumed linear heads where external id is not null`() {
        database.transaction {
            vaultFiller.fillWithSomeTestLinearStates(1, "TEST1")
            vaultFiller.fillWithSomeTestLinearStates(1)
            vaultFiller.fillWithSomeTestLinearStates(1, "TEST3")
            // 3 unconsumed states (two with an external ID)
            val results = builder {
                val externalIdCondition = VaultSchemaV1.VaultLinearStates::externalId.notNull()
                val externalIdCustomCriteria = VaultCustomQueryCriteria(externalIdCondition)

                vaultService.queryBy<LinearState>(externalIdCustomCriteria)
            }
            assertThat(results.states).hasSize(2)
        }
    }

    @Test
    fun `enriched and overridden composite query handles defaults correctly`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 2, DUMMY_CASH_ISSUER)
            vaultFiller.fillWithSomeTestCommodity(Amount(100, Commodity.getInstance("FCOJ")!!), notaryServices, DUMMY_OBLIGATION_ISSUER.ref(1))
            vaultFiller.fillWithSomeTestLinearStates(1, "ABC")
            vaultFiller.fillWithSomeTestDeals(listOf("123"))
            // Base criteria
            val baseCriteria = VaultQueryCriteria(notary = listOf(DUMMY_NOTARY),
                    status = Vault.StateStatus.CONSUMED)

            // Enrich and override QueryCriteria with additional default attributes (such as soft locks)
            val enrichedCriteria = VaultQueryCriteria(contractStateTypes = setOf(DealState::class.java), // enrich
                    softLockingCondition = QueryCriteria.SoftLockingCondition(QueryCriteria.SoftLockingType.UNLOCKED_AND_SPECIFIED, listOf(UUID.randomUUID())),
                    status = Vault.StateStatus.UNCONSUMED)  // override
            // Sorting
            val sortAttribute = SortAttribute.Standard(Sort.CommonStateAttribute.STATE_REF)
            val sorter = Sort(setOf(Sort.SortColumn(sortAttribute, Sort.Direction.ASC)))

            // Execute query
            val results = services.vaultService.queryBy<FungibleAsset<*>>(baseCriteria and enrichedCriteria, sorter).states
            assertThat(results).hasSize(4)
        }
    }

    @Test
    fun unconsumedCashStatesForSpending_single_issuer_reference() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(1000.DOLLARS, notaryServices, 1, DUMMY_CASH_ISSUER)
            this.session.flush()

            val builder = TransactionBuilder()
            val issuer = DUMMY_CASH_ISSUER
            val exitStates = AbstractCashSelection
                    .getInstance { services.jdbcSession().metaData }
                    .unconsumedCashStatesForSpending(services, 300.DOLLARS, setOf(issuer.party),
                            builder.notary, builder.lockId, setOf(issuer.reference))

            assertThat(exitStates).hasSize(1)
            assertThat(exitStates[0].state.data.amount.quantity).isEqualTo(100000)
        }
    }

    @Test
    fun `unconsumedCashStatesForSpending single issuer reference not matching`() {
        database.transaction {
            vaultFiller.fillWithSomeTestCash(1000.DOLLARS, notaryServices, 1, DUMMY_CASH_ISSUER)

            val builder = TransactionBuilder()
            val issuer = DUMMY_CASH_ISSUER
            val exitStates = AbstractCashSelection
                    .getInstance { services.jdbcSession().metaData }
                    .unconsumedCashStatesForSpending(services, 300.DOLLARS, setOf(issuer.party),
                            builder.notary, builder.lockId, setOf(OpaqueBytes.of(13)))
            assertThat(exitStates).hasSize(0)
        }
    }

    @Test
    fun `record a transaction with number of inputs greater than vault page size`() {
        val notary = dummyNotary
        val issuerKey = notary.keyPair
        val signatureMetadata = SignatureMetadata(services.myInfo.platformVersion, Crypto.findSignatureScheme(issuerKey.public).schemeNumberID)
        val states = database.transaction {
            vaultFiller.fillWithSomeTestLinearStates(PageSpecification().pageSize + 1).states
        }

        database.transaction {
            val statesExitingTx = TransactionBuilder(notary.party).withItems(*states.toList().toTypedArray()).addCommand(dummyCommand())
            val signedStatesExitingTx = services.signInitialTransaction(statesExitingTx).withAdditionalSignature(issuerKey, signatureMetadata)

            assertThatCode { services.recordTransactions(signedStatesExitingTx) }.doesNotThrowAnyException()
        }
    }

    /**
     *  USE CASE demonstrations (outside of mainline Corda)
     *
     *  1) Template / Tutorial CorDapp service using Vault API Custom Query to access attributes of IOU State
     *  2) Template / Tutorial Flow using a DB session to execute a custom query
     *  3) Template / Tutorial CorDapp service query extension executing Named Queries via JPA
     *  4) Advanced pagination queries using Spring Data (and/or Hibernate/JPQL)
     */
}

class VaultQueryTests : VaultQueryTestsBase(), VaultQueryParties by delegate {

    companion object {
        val delegate = VaultQueryTestRule()
    }

    @Rule
    @JvmField
    val vaultQueryTestRule = delegate

    /**
     * Dynamic trackBy() tests are H2 only, since rollback stops events being emitted.
     */

    @Test
    fun trackCashStates_unconsumed() {
        val updates = database.transaction {
            val updates =
            // DOCSTART VaultQueryExample15
                    vaultService.trackBy<Cash.State>().updates     // UNCONSUMED default
            // DOCEND VaultQueryExample15

            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 5, DUMMY_CASH_ISSUER)
            val linearStates = vaultFiller.fillWithSomeTestLinearStates(10).states
            val dealStates = vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789")).states
            // add more cash
            vaultFiller.fillWithSomeTestCash(100.POUNDS, notaryServices, 1, DUMMY_CASH_ISSUER)
            // add another deal
            vaultFiller.fillWithSomeTestDeals(listOf("SAMPLE DEAL"))
            this.session.flush()

            // consume stuff
            consumeCash(100.DOLLARS)
            vaultFiller.consumeDeals(dealStates.toList())
            vaultFiller.consumeLinearStates(linearStates.toList())

            updates
        }

        updates.expectEvents {
            sequence(
                    expect { (consumed, produced, flowId) ->
                        require(flowId == null) {}
                        require(consumed.isEmpty()) {}
                        require(produced.size == 5) {}
                    },
                    expect { (consumed, produced, flowId) ->
                        require(flowId == null) {}
                        require(consumed.isEmpty()) {}
                        require(produced.size == 1) {}
                    }
            )
        }
    }

    @Test
    fun trackCashStates_consumed() {

        val updates = database.transaction {
            val criteria = VaultQueryCriteria(status = Vault.StateStatus.CONSUMED)
            val updates = vaultService.trackBy<Cash.State>(criteria).updates

            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 5, DUMMY_CASH_ISSUER)
            val linearStates = vaultFiller.fillWithSomeTestLinearStates(10).states
            val dealStates = vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789")).states
            // add more cash
            vaultFiller.fillWithSomeTestCash(100.POUNDS, notaryServices, 1, DUMMY_CASH_ISSUER)
            // add another deal
            vaultFiller.fillWithSomeTestDeals(listOf("SAMPLE DEAL"))
            this.session.flush()

            consumeCash(100.POUNDS)

            // consume more stuff
            consumeCash(100.DOLLARS)
            vaultFiller.consumeDeals(dealStates.toList())
            vaultFiller.consumeLinearStates(linearStates.toList())

            updates
        }

        updates.expectEvents {
            sequence(
                    expect { (consumed, produced, flowId) ->
                        require(flowId == null) {}
                        require(consumed.size == 1) {}
                        require(produced.isEmpty()) {}
                    },
                    expect { (consumed, produced, flowId) ->
                        require(flowId == null) {}
                        require(consumed.size == 5) {}
                        require(produced.isEmpty()) {}
                    }
            )
        }
    }

    @Test
    fun trackCashStates_all() {
        val updates = database.transaction {
            val updates =
                    database.transaction {
                        val criteria = VaultQueryCriteria(status = Vault.StateStatus.ALL)
                        vaultService.trackBy<Cash.State>(criteria).updates
                    }
            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 5, DUMMY_CASH_ISSUER)
            val linearStates = vaultFiller.fillWithSomeTestLinearStates(10).states
            val dealStates = vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789")).states
            // add more cash
            vaultFiller.fillWithSomeTestCash(100.POUNDS, notaryServices, 1, DUMMY_CASH_ISSUER)
            // add another deal
            vaultFiller.fillWithSomeTestDeals(listOf("SAMPLE DEAL"))
            this.session.flush()

            // consume stuff
            consumeCash(99.POUNDS)

            consumeCash(100.DOLLARS)
            vaultFiller.consumeDeals(dealStates.toList())
            vaultFiller.consumeLinearStates(linearStates.toList())

            updates
        }

        updates.expectEvents {
            sequence(
                    expect { (consumed, produced, flowId) ->
                        require(flowId == null) {}
                        require(consumed.isEmpty()) {}
                        require(produced.size == 5) {}
                    },
                    expect { (consumed, produced, flowId) ->
                        require(flowId == null) {}
                        require(consumed.isEmpty()) {}
                        require(produced.size == 1) {}
                    },
                    expect { (consumed, produced, flowId) ->
                        require(flowId == null) {}
                        require(consumed.size == 1) {}
                        require(produced.size == 1) {}
                    },
                    expect { (consumed, produced, flowId) ->
                        require(flowId == null) {}
                        require(consumed.size == 5) {}
                        require(produced.isEmpty()) {}
                    }
            )
        }
    }

    @Test
    fun trackLinearStates() {

        val updates = database.transaction {
            // DOCSTART VaultQueryExample16
            val (snapshot, updates) = vaultService.trackBy<LinearState>()
            // DOCEND VaultQueryExample16
            assertThat(snapshot.states).hasSize(0)

            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            val linearStates = vaultFiller.fillWithSomeTestLinearStates(10).states
            val dealStates = vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789")).states
            // add more cash
            vaultFiller.fillWithSomeTestCash(100.POUNDS, notaryServices, 1, DUMMY_CASH_ISSUER)
            // add another deal
            vaultFiller.fillWithSomeTestDeals(listOf("SAMPLE DEAL"))
            this.session.flush()

            // consume stuff
            consumeCash(100.DOLLARS)
            vaultFiller.consumeDeals(dealStates.toList())
            vaultFiller.consumeLinearStates(linearStates.toList())

            updates
        }

        updates.expectEvents {
            sequence(
                    expect { (consumed, produced, flowId) ->
                        require(flowId == null) {}
                        require(consumed.isEmpty()) {}
                        require(produced.size == 10) {}
                    },
                    expect { (consumed, produced, flowId) ->
                        require(flowId == null) {}
                        require(consumed.isEmpty()) {}
                        require(produced.size == 3) {}
                    },
                    expect { (consumed, produced, flowId) ->
                        require(flowId == null) {}
                        require(consumed.isEmpty()) {}
                        require(produced.size == 1) {}
                    }
            )
        }
    }

    @Test
    fun trackDealStates() {
        val updates = database.transaction {
            // DOCSTART VaultQueryExample17
            val (snapshot, updates) = vaultService.trackBy<DealState>()
            // DOCEND VaultQueryExample17
            assertThat(snapshot.states).hasSize(0)

            vaultFiller.fillWithSomeTestCash(100.DOLLARS, notaryServices, 3, DUMMY_CASH_ISSUER)
            val linearStates = vaultFiller.fillWithSomeTestLinearStates(10).states
            val dealStates = vaultFiller.fillWithSomeTestDeals(listOf("123", "456", "789")).states
            // add more cash
            vaultFiller.fillWithSomeTestCash(100.POUNDS, notaryServices, 1, DUMMY_CASH_ISSUER)
            // add another deal
            vaultFiller.fillWithSomeTestDeals(listOf("SAMPLE DEAL"))
            this.session.flush()

            // consume stuff
            consumeCash(100.DOLLARS)
            vaultFiller.consumeDeals(dealStates.toList())
            vaultFiller.consumeLinearStates(linearStates.toList())

            updates
        }

        updates.expectEvents {
            sequence(
                    expect { (consumed, produced, flowId) ->
                        require(flowId == null) {}
                        require(consumed.isEmpty()) {}
                        require(produced.size == 3) {}
                    },
                    expect { (consumed, produced, flowId) ->
                        require(flowId == null) {}
                        require(consumed.isEmpty()) {}
                        require(produced.size == 1) {}
                    }
            )
        }
    }
}