package app.mymemory.ledger

import app.mymemory.ledger.data.Category
import app.mymemory.ledger.data.Instrument
import app.mymemory.ledger.data.LedgerDao
import app.mymemory.ledger.data.LedgerTxn
import app.mymemory.ledger.data.PartyKind
import app.mymemory.ledger.data.TxnKind
import app.mymemory.ledger.parse.Names
import app.mymemory.ledger.parse.ParsedTxn

/**
 * The same payment usually shows up several times: bank SMS, UPI-app SMS, card SMS, e-mail receipt.
 * Two records are the same transaction when a weighted score clears THRESHOLD.
 *
 * Hard rules (decide immediately):
 *   - same reference number          -> same transaction
 *   - different reference numbers    -> different transactions (two identical coffees stay two)
 *   - different account last-4       -> different
 *   - direction / amount differ      -> different (the DB query already guarantees equality)
 * Otherwise score = time closeness + "seen from a different channel" + counterparty / platform /
 * account / instrument agreement. Two messages from the SAME sender are penalised, because a bank
 * does not send two alerts for one payment but you can buy two coffees five minutes apart.
 */
object Deduper {
    const val THRESHOLD = 55
    private const val MIN = 60_000L
    private const val HOUR = 3_600_000L
    const val NEVER = Int.MIN_VALUE

    data class View(
        val direction: String,
        val amountPaise: Long,
        val time: Long,
        val ref: String?,
        val last4: String?,
        val party: String?,
        val platform: String?,
        val instrument: String?,
        val channels: Set<String>,
    )

    fun score(n: View, e: View): Int {
        if (n.direction != e.direction || n.amountPaise != e.amountPaise) return NEVER
        if (n.ref != null && e.ref != null) return if (n.ref == e.ref) 100 else NEVER
        if (n.last4 != null && e.last4 != null && n.last4 != e.last4) return NEVER

        val dt = kotlin.math.abs(n.time - e.time)
        var s = when {
            dt <= 2 * MIN -> 45
            dt <= 10 * MIN -> 35
            dt <= HOUR -> 20
            dt <= 36 * HOUR -> 10
            else -> return NEVER
        }
        s += if (n.channels.any { it in e.channels }) -25 else 20
        Names.similar(n.party, e.party)?.let { s += if (it) 25 else -30 }
        if (n.platform != null && e.platform != null && n.platform.equals(e.platform, ignoreCase = true)) s += 15
        if (n.last4 != null && n.last4 == e.last4) s += 15
        if (n.instrument != null && n.instrument == e.instrument) s += 5
        return s
    }

    private fun last4(hint: String?): String? = hint?.takeIf { h -> h.length == 4 && h.all { it.isDigit() } }

    fun view(p: ParsedTxn, channel: String) = View(
        p.direction.name, p.amountPaise, p.occurredAt, p.referenceId, last4(p.accountHint),
        p.counterparty, p.platform, p.instrument.name, setOf(channel)
    )

    fun view(t: LedgerTxn) = View(
        t.direction, t.amountPaise, t.occurredAt, t.referenceId, last4(t.accountHint),
        t.counterparty, t.platform, t.instrument, t.channels.split(',').toSet()
    )

    suspend fun findMatch(dao: LedgerDao, p: ParsedTxn, channel: String): LedgerTxn? {
        val window = 36 * HOUR
        val n = view(p, channel)
        return dao.candidates(p.direction.name, p.amountPaise, p.occurredAt - window, p.occurredAt + window)
            .map { it to score(n, view(it)) }
            .filter { it.second >= THRESHOLD }
            .maxByOrNull { it.second }
            ?.first
    }

    /** Fold a second sighting into the canonical row: fill gaps, never overwrite what the user set. */
    fun merge(e: LedgerTxn, p: ParsedTxn, channel: String): LedgerTxn {
        val eName = e.counterparty
        val pName = p.counterparty
        val name = when {
            eName == null -> pName
            '@' in eName && pName != null && '@' !in pName -> pName
            else -> eName
        }
        val takeNew = !e.userEdited && (e.cat == Category.OTHER || p.confidence > e.confidence + 0.1f)
        val kind = when {
            e.userEdited -> e.txnKind
            e.txnKind == TxnKind.PERSON_TRANSFER && p.kind != TxnKind.PERSON_TRANSFER -> p.kind
            else -> e.txnKind
        }
        val category = when {
            e.userEdited -> e.cat
            kind.isPeopleLedger || kind == TxnKind.PERSON_TRANSFER -> Category.PEOPLE
            takeNew -> p.category
            else -> e.cat
        }
        return e.copy(
            counterparty = name,
            counterpartyKey = if (e.userEdited) e.counterpartyKey else Names.key(name),
            partyKind = if (e.party == PartyKind.UNKNOWN) p.partyKind.name else e.partyKind,
            platform = e.platform ?: p.platform,
            item = e.item ?: p.item,
            referenceId = e.referenceId ?: p.referenceId,
            accountHint = e.accountHint ?: p.accountHint,
            instrument = if (e.instrument == Instrument.OTHER.name) p.instrument.name else e.instrument,
            category = category.name,
            kind = kind.name,
            confidence = minOf(1f, maxOf(e.confidence, p.confidence) + 0.05f), // two sources agree
            needsReview = if (e.userEdited) false else kind == TxnKind.PERSON_TRANSFER || (e.needsReview && p.needsReview),
            channels = (e.channels.split(',') + channel).toSet().joinToString(","),
        )
    }
}
