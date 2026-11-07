package app.mymemory.ledger.parse

import app.mymemory.ledger.data.Category
import app.mymemory.ledger.data.Direction
import app.mymemory.ledger.data.Instrument
import app.mymemory.ledger.data.LedgerTxn
import app.mymemory.ledger.data.PartyKind
import app.mymemory.ledger.data.TxnKind

/** Output of the parser, before dedup. */
data class ParsedTxn(
    val direction: Direction,
    val amountPaise: Long,
    val occurredAt: Long,
    val instrument: Instrument,
    val accountHint: String?,
    val counterparty: String?,
    val partyKind: PartyKind,
    val platform: String?,
    val item: String?,
    val referenceId: String?,
    val category: Category,
    val kind: TxnKind,
    val confidence: Float,
    val needsReview: Boolean,
    val note: String? = null,
)

fun ParsedTxn.toEntity(channel: String) = LedgerTxn(
    direction = direction.name,
    amountPaise = amountPaise,
    occurredAt = occurredAt,
    instrument = instrument.name,
    accountHint = accountHint,
    counterparty = counterparty,
    counterpartyKey = Names.key(counterparty),
    partyKind = partyKind.name,
    platform = platform,
    item = item,
    referenceId = referenceId,
    category = category.name,
    kind = kind.name,
    confidence = confidence,
    needsReview = needsReview,
    note = note,
    channels = channel,
    createdAt = System.currentTimeMillis(),
)
