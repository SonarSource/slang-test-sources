package io.ktor.auth

import io.ktor.application.*
import io.ktor.pipeline.*

/**
 * Represents authentication challenging procedure requested by authentication mechanism
 */
class AuthenticationProcedureChallenge {
    internal val register = mutableListOf<Pair<AuthenticationFailedCause, PipelineInterceptor<AuthenticationProcedureChallenge, ApplicationCall>>>()

    /**
     * List of currently installed challenges
     */
    val challenges: List<PipelineInterceptor<AuthenticationProcedureChallenge, ApplicationCall>>
        get() = register.filter { it.first !is AuthenticationFailedCause.Error }.sortedBy {
            when (it.first) {
                AuthenticationFailedCause.InvalidCredentials -> 1
                AuthenticationFailedCause.NoCredentials -> 2
                else -> throw NoWhenBranchMatchedException("${it.first}")
            }
        }.map { it.second }

    /**
     * Represents if a challenge was successfully sent to the client and challenging should be stopped
     */
    @Volatile
    var completed = false
        private set

    /**
     * Completes a challenging procedure
     */
    fun complete() {
        completed = true
    }

    override fun toString(): String = "AuthenticationProcedureChallenge"
}