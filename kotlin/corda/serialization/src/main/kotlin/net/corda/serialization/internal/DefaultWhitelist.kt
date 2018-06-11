package net.corda.serialization.internal

import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.utilities.NetworkHostAndPort
import org.apache.activemq.artemis.api.core.SimpleString
import rx.Notification
import rx.exceptions.OnErrorNotImplementedException
import sun.security.x509.X509CertImpl
import java.security.cert.CRLReason
import java.util.*

/**
 * NOTE: We do not whitelist [HashMap] or [HashSet] since they are unstable under serialization.
 */
object DefaultWhitelist : SerializationWhitelist {
    override val whitelist =
            listOf(Array<Any>(0, {}).javaClass,
                    Notification::class.java,
                    Notification.Kind::class.java,
                    ArrayList::class.java,
                    Pair::class.java,
                    Triple::class.java,
                    ByteArray::class.java,
                    UUID::class.java,
                    LinkedHashSet::class.java,
                    Currency::class.java,
                    listOf(Unit).javaClass, // SingletonList
                    setOf(Unit).javaClass, // SingletonSet
                    mapOf(Unit to Unit).javaClass, // SingletonMap
                    NetworkHostAndPort::class.java,
                    SimpleString::class.java,
                    StringBuffer::class.java,
                    Unit::class.java,
                    java.io.ByteArrayInputStream::class.java,
                    java.lang.Class::class.java,
                    java.math.BigDecimal::class.java,

                    // Matches the list in TimeSerializers.addDefaultSerializers:
                    java.time.Duration::class.java,
                    java.time.Instant::class.java,
                    java.time.LocalDate::class.java,
                    java.time.LocalDateTime::class.java,
                    java.time.LocalTime::class.java,
                    java.time.ZoneOffset::class.java,
                    java.time.ZoneId::class.java,
                    java.time.OffsetTime::class.java,
                    java.time.OffsetDateTime::class.java,
                    java.time.ZonedDateTime::class.java,
                    java.time.Year::class.java,
                    java.time.YearMonth::class.java,
                    java.time.MonthDay::class.java,
                    java.time.Period::class.java,
                    java.time.DayOfWeek::class.java, // No custom serializer but it's an enum.
                    java.time.Month::class.java, // No custom serializer but it's an enum.

                    java.util.Collections.emptyMap<Any, Any>().javaClass,
                    java.util.Collections.emptySet<Any>().javaClass,
                    java.util.Collections.emptyList<Any>().javaClass,
                    java.util.LinkedHashMap::class.java,
                    BitSet::class.java,
                    OnErrorNotImplementedException::class.java,
                    StackTraceElement::class.java,

                    // Implementation of X509Certificate.
                    X509CertImpl::class.java,
                    CRLReason::class.java
            )
}
