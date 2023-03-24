package io.ktor.network.selector

import kotlinx.coroutines.*

/**
 * A selectable entity with selectable NIO [channel], [interestedOps] subscriptions.
 */
public expect interface Selectable

@Suppress("KDocMissingDocumentation")
public class ClosedChannelCancellationException : CancellationException("Closed channel.")
