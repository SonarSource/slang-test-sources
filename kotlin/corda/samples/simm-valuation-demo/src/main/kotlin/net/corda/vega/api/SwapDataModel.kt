package net.corda.vega.api

import com.opengamma.strata.product.common.BuySell
import net.corda.core.identity.Party
import net.corda.vega.contracts.SwapData
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Represents a SwapData object without any of the functionality. This was created because serialising and
 * deserialising the SwapData class with Jackson led to circular dependency errors. Also simplifies the
 * JSON required to create a swap.
 */
data class SwapDataModel(
        val id: String,
        val description: String,
        val tradeDate: LocalDate,
        val convention: String,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val buySell: BuySell,
        val notional: BigDecimal,
        val fixedRate: BigDecimal) {

    /**
     * Turn this model into the internal representation of SwapData.
     */
    fun toData(buyer: Party, seller: Party): SwapData {
        return SwapData(
                Pair("swap", id), Pair("party", buyer.owningKey), Pair("party", seller.owningKey), description, tradeDate, convention, startDate, endDate, notional, fixedRate
        )
    }
}
