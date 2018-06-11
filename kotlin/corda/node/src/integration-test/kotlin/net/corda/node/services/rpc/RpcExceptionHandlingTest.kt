package net.corda.node.services.rpc

import co.paralleluniverse.fibers.Suspendable
import net.corda.ClientRelevantException
import net.corda.core.CordaRuntimeException
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.unwrap
import net.corda.node.services.Permissions
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.NodeParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.hibernate.exception.GenericJDBCException
import org.junit.Test
import java.sql.SQLException

class RpcExceptionHandlingTest {

    private val user = User("mark", "dadada", setOf(Permissions.all()))
    private val users = listOf(user)

    @Test
    fun `rpc client handles exceptions thrown on node side`() {
        driver(DriverParameters(startNodesInProcess = true, notarySpecs = emptyList())) {

            val node = startNode(NodeParameters(rpcUsers = users)).getOrThrow()

            assertThatThrownBy { node.rpc.startFlow(::Flow).returnValue.getOrThrow() }.isInstanceOfSatisfying(CordaRuntimeException::class.java) { exception ->

                assertThat(exception).hasNoCause()
                assertThat(exception.stackTrace).isEmpty()
            }
        }
    }

    @Test
    fun `rpc client handles client-relevant exceptions thrown on node side`() {
        driver(DriverParameters(startNodesInProcess = true, notarySpecs = emptyList())) {

            val node = startNode(NodeParameters(rpcUsers = users)).getOrThrow()
            val clientRelevantMessage = "This is for the players!"

            assertThatThrownBy { node.rpc.startFlow(::ClientRelevantErrorFlow, clientRelevantMessage).returnValue.getOrThrow() }.isInstanceOfSatisfying(CordaRuntimeException::class.java) { exception ->

                assertThat(exception).hasNoCause()
                assertThat(exception.stackTrace).isEmpty()
                assertThat(exception.message).isEqualTo(clientRelevantMessage)
            }
        }
    }

    @Test
    fun `FlowException is received by the RPC client`() {
        driver(DriverParameters(startNodesInProcess = true, notarySpecs = emptyList())) {
            val node = startNode(NodeParameters(rpcUsers = users)).getOrThrow()
            val exceptionMessage = "Flow error!"
            assertThatThrownBy { node.rpc.startFlow(::FlowExceptionFlow, exceptionMessage).returnValue.getOrThrow() }
                    .isInstanceOfSatisfying(FlowException::class.java) { exception ->
                        assertThat(exception).hasNoCause()
                        assertThat(exception.stackTrace).isEmpty()
                        assertThat(exception.message).isEqualTo(exceptionMessage)
                    }
        }
    }

    @Test
    fun `rpc client handles exceptions thrown on counter-party side`() {
        driver(DriverParameters(startNodesInProcess = true, notarySpecs = emptyList())) {

            val nodeA = startNode(NodeParameters(providedName = ALICE_NAME, rpcUsers = users)).getOrThrow()
            val nodeB = startNode(NodeParameters(providedName = BOB_NAME, rpcUsers = users)).getOrThrow()

            assertThatThrownBy { nodeA.rpc.startFlow(::InitFlow, nodeB.nodeInfo.singleIdentity()).returnValue.getOrThrow() }.isInstanceOfSatisfying(CordaRuntimeException::class.java) { exception ->

                assertThat(exception).hasNoCause()
                assertThat(exception.stackTrace).isEmpty()
            }
        }
    }
}

@StartableByRPC
class Flow : FlowLogic<String>() {
    @Suspendable
    override fun call(): String {
        throw GenericJDBCException("Something went wrong!", SQLException("Oops!"))
    }
}

@StartableByRPC
@InitiatingFlow
class InitFlow(private val party: Party) : FlowLogic<String>() {
    @Suspendable
    override fun call(): String {
        val session = initiateFlow(party)
        return session.sendAndReceive<String>("hey").unwrap { it }
    }
}

@InitiatedBy(InitFlow::class)
class InitiatedFlow(private val initiatingSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        initiatingSession.receive<String>().unwrap { it }
        throw GenericJDBCException("Something went wrong!", SQLException("Oops!"))
    }
}

@StartableByRPC
class ClientRelevantErrorFlow(private val message: String) : FlowLogic<String>() {
    @Suspendable
    override fun call(): String = throw ClientRelevantException(message, SQLException("Oops!"))
}

@StartableByRPC
class FlowExceptionFlow(private val message: String) : FlowLogic<String>() {
    @Suspendable
    override fun call(): String = throw FlowException(message)
}
