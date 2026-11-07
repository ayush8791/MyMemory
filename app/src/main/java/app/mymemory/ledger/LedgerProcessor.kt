package app.mymemory.ledger

import app.mymemory.ledger.data.Category
import app.mymemory.ledger.data.Direction
import app.mymemory.ledger.data.LedgerDao
import app.mymemory.ledger.data.LedgerTxn
import app.mymemory.ledger.data.RawMessage
import app.mymemory.ledger.data.RawStatus
import app.mymemory.ledger.data.TxnKind
import app.mymemory.ledger.parse.ParsedTxn
import app.mymemory.ledger.parse.TxnParser
import app.mymemory.ledger.parse.toEntity
import java.util.UUID

data class ManualResult(val ok: Boolean, val message: String)

/** Pending raw messages -> parse -> dedup -> ledger rows. */
class LedgerProcessor(private val dao: LedgerDao, private val parser: TxnParser) {

    /** Returns how many raw messages were handled (0 means the queue is drained). */
    suspend fun processPending(limit: Int = 10): Int {
        val batch = dao.pending(limit)
        for (raw in batch) {
            when (val r = parser.parse(raw)) {
                TxnParser.Result.NotTxn -> dao.markRaw(raw.id, RawStatus.SKIPPED.name, null)
                TxnParser.Result.Retry ->
                    dao.markRaw(raw.id, if (raw.attempts >= 2) RawStatus.FAILED.name else RawStatus.PENDING.name, null)
                is TxnParser.Result.Ok -> dao.markRaw(raw.id, RawStatus.PARSED.name, store(r.txn, raw))
            }
        }
        return batch.size
    }

    /** Dictated / typed note such as "lent 500 to Rahul for lunch". Returns a message for the user. */
    suspend fun addManual(text: String): ManualResult {
        val raw = RawMessage(
            source = "MANUAL", externalId = UUID.randomUUID().toString(), sender = "me",
            receivedAt = System.currentTimeMillis(), body = text.trim(),
        )
        return when (val r = parser.parse(raw, manual = true)) {
            TxnParser.Result.Retry -> ManualResult(false, "Import the Gemma model in Settings first. Typed notes need it.")
            TxnParser.Result.NotTxn -> ManualResult(false, "I couldn't find an amount in that. Try \"lent 500 to Rahul for lunch\".")
            is TxnParser.Result.Ok -> {
                val id = store(r.txn, raw)
                dao.insertRaw(raw.copy(status = RawStatus.PARSED.name, txnId = id))
                ManualResult(true, "Added.")
            }
        }
    }

    private suspend fun store(p: ParsedTxn, raw: RawMessage): Long {
        val channel = "${raw.source}:${raw.sender.lowercase()}"
        if (raw.source != "MANUAL") {
            Deduper.findMatch(dao, p, channel)?.let { existing ->
                dao.updateTxn(Deduper.merge(existing, p, channel))
                return existing.id
            }
        }
        return dao.insertTxn(suggestRepayment(p.toEntity(channel)))
    }

    /**
     * An unexplained transfer from someone you lent money to is probably them paying you back
     * (and the reverse for money you borrowed). Pre-label it, but keep it in review for a one-tap check.
     */
    private suspend fun suggestRepayment(t: LedgerTxn): LedgerTxn {
        val key = t.counterpartyKey
        if (t.txnKind != TxnKind.PERSON_TRANSFER || key == null) return t
        val net = Balances.net(dao.peopleLedgerFor(key))
        val newKind = when {
            t.dir == Direction.CREDIT && net > 0 -> TxnKind.REPAYMENT_RECEIVED
            t.dir == Direction.DEBIT && net < 0 -> TxnKind.REPAYMENT_MADE
            else -> return t
        }
        return t.copy(kind = newKind.name, category = Category.PEOPLE.name, needsReview = true)
    }
}
