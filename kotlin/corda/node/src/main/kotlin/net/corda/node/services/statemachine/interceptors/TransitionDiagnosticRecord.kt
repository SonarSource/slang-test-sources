package net.corda.node.services.statemachine.interceptors

import net.corda.core.flows.StateMachineRunId
import net.corda.node.services.statemachine.transitions.FlowContinuation
import net.corda.node.services.statemachine.Event
import net.corda.node.services.statemachine.StateMachineState
import net.corda.node.services.statemachine.transitions.TransitionResult
import net.corda.node.utilities.ObjectDiffer
import java.time.Instant

/**
 * This is a diagnostic record that stores information about a state machine transition and provides pretty printing
 * by diffing the two states.
 */
data class TransitionDiagnosticRecord(
        val timestamp: Instant,
        val flowId: StateMachineRunId,
        val previousState: StateMachineState,
        val nextState: StateMachineState,
        val event: Event,
        val transition: TransitionResult,
        val continuation: FlowContinuation
) {
    override fun toString(): String {
        val diffIntended = ObjectDiffer.diff(previousState, transition.newState)
        val diffNext = ObjectDiffer.diff(previousState, nextState)
        return (
                listOf(
                        "",
                        " --- Transition of flow $flowId ---",
                        "  Timestamp: $timestamp",
                        "  Event: $event",
                        "  Actions: ",
                        "    ${transition.actions.joinToString("\n    ")}",
                        "  Continuation: ${transition.continuation}"
                ) +
                        if (diffIntended != diffNext) {
                            listOf(
                                    "  Diff between previous and intended state:",
                                    "${diffIntended?.toPaths()?.joinToString("")}"
                            )
                        } else {
                            emptyList()
                        } + listOf(

                        "  Diff between previous and next state:",
                        "${diffNext?.toPaths()?.joinToString("")}"
                )
                ).joinToString("\n")
    }
}
