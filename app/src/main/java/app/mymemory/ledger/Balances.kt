package app.mymemory.ledger

import app.mymemory.ledger.data.Category
import app.mymemory.ledger.data.Direction
import app.mymemory.ledger.data.LedgerTxn
import app.mymemory.ledger.data.MoneyFlow
import app.mymemory.ledger.data.TxnKind
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** net > 0: they owe you.  net < 0: you owe them. */
data class PersonBalance(val key: String, val name: String, val netPaise: Long, val entries: List<LedgerTxn>)

data class MonthSummary(val spentPaise: Long, val incomePaise: Long)

object Balances {
    fun signed(t: LedgerTxn): Long = when (t.txnKind) {
        TxnKind.LENT, TxnKind.REPAYMENT_MADE -> t.amountPaise
        TxnKind.BORROWED, TxnKind.REPAYMENT_RECEIVED -> -t.amountPaise
        else -> 0L
    }

    fun net(entries: List<LedgerTxn>): Long = entries.sumOf { signed(it) }

    fun people(all: List<LedgerTxn>): List<PersonBalance> =
        all.filter { it.counterpartyKey != null && it.txnKind.isPeopleLedger }
            .groupBy { it.counterpartyKey!! }
            .map { (key, rows) ->
                val sorted = rows.sortedByDescending { it.occurredAt }
                PersonBalance(key, sorted.first().counterparty ?: key, net(rows), sorted)
            }
            .sortedByDescending { kotlin.math.abs(it.netPaise) }

    /** Expenses net of refunds, and income. Lending, own-account moves and card-bill payments are not spending. */
    fun month(all: List<LedgerTxn>, month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): MonthSummary {
        var spent = 0L
        var income = 0L
        for (t in all) {
            if (YearMonth.from(Instant.ofEpochMilli(t.occurredAt).atZone(zone)) != month) continue
            val flow = t.cat.flow
            if (t.dir == Direction.DEBIT) {
                if (flow == MoneyFlow.SPEND) spent += t.amountPaise
            } else when {
                flow == MoneyFlow.REFUND -> spent -= t.amountPaise
                flow == MoneyFlow.INCOME || (t.cat == Category.OTHER && t.txnKind == TxnKind.NORMAL) -> income += t.amountPaise
            }
        }
        return MonthSummary(spent.coerceAtLeast(0), income)
    }
}
