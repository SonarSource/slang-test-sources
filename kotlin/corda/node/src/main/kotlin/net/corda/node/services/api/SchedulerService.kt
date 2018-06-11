package net.corda.node.services.api

import net.corda.core.contracts.ScheduledStateRef
import net.corda.core.contracts.StateRef

/**
 * Provides access to schedule activity at some point in time.  This interface might well be expanded to
 * increase the feature set in the future.
 *
 * If the point in time is in the past, the expectation is that the activity will happen shortly after it is scheduled.
 *
 * The main consumer initially is an observer of the vault to schedule activities based on transactions as they are
 * recorded.
 */
interface SchedulerService {
    /**
     * Schedule a new activity for a TX output, probably because it was just produced.
     *
     * Only one activity can be scheduled for a particular [StateRef] at any one time.  Scheduling a [ScheduledStateRef]
     * replaces any previously scheduled [ScheduledStateRef] for any one [StateRef].
     */
    fun scheduleStateActivity(action: ScheduledStateRef)

    /** Unschedule all activity for a TX output, probably because it was consumed. */
    fun unscheduleStateActivity(ref: StateRef)
}