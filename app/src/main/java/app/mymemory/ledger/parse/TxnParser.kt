package app.mymemory.ledger.parse

import app.mymemory.ai.LlmEngine
import app.mymemory.ledger.data.Category
import app.mymemory.ledger.data.Direction
import app.mymemory.ledger.data.Instrument
import app.mymemory.ledger.data.LedgerDao
import app.mymemory.ledger.data.MoneyFlow
import app.mymemory.ledger.data.PartyKind
import app.mymemory.ledger.data.RawMessage
import app.mymemory.ledger.data.TxnKind
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Message -> structured transaction.
 *
 *  1. Rules decide cheaply whether a message could be a transaction at all.
 *  2. Gemma extracts the full record (merchant, item, category, person transfer, ...).
 *  3. Everything the model says is checked against the original text. A model that invents an
 *     amount or a reference number gets overruled by the text, and the row goes to review.
 *  4. If the model is missing or fails, the rule hints still produce a usable (reviewable) row.
 */
class TxnParser(private val llm: LlmEngine, private val dao: LedgerDao) {

    sealed interface Result {
        data class Ok(val txn: ParsedTxn) : Result
        data object NotTxn : Result
        data object Retry : Result
    }

    suspend fun parse(raw: RawMessage, manual: Boolean = false): Result {
        val hints = RuleParser.parse(raw.body)
        val candidate = manual || hints != null ||
            (raw.source == "GMAIL" && RuleParser.hasAmount(raw.body) && !RuleParser.isOtp(raw.body))
        if (!candidate) return Result.NotTxn

        // No model: rules only (not possible for dictated notes, which need real language understanding).
        if (!llm.isReady()) {
            return if (!manual && hints != null) Result.Ok(fromRules(raw, hints)) else Result.Retry
        }

        val json: JSONObject? = try {
            Prompt.extractJson(llm.generate(Prompt.build(raw.body, raw.sender, raw.receivedAt, hints, manual)))
        } catch (t: Throwable) {
            null
        }
        if (json == null) {
            return if (!manual && hints != null) Result.Ok(fromRules(raw, hints)) else Result.Retry
        }
        if (!json.optBoolean("is_transaction", false)) return Result.NotTxn
        return enrich(raw, json, hints, manual)?.let { Result.Ok(it) } ?: Result.NotTxn
    }

    private suspend fun enrich(raw: RawMessage, j: JSONObject, hints: RuleHints?, manual: Boolean): ParsedTxn? {
        var review = false
        var confidence = j.optDouble("confidence", 0.7).toFloat().coerceIn(0f, 1f)

        // ---- amount: must be backed by the text ----
        val modelAmount = j.opt("amount")?.toString()?.replace(",", "")?.toDoubleOrNull()?.let { Math.round(it * 100) }
        val inText = RuleParser.allAmounts(raw.body)
        val amount: Long = when {
            manual -> modelAmount ?: return null           // spoken numbers ("five hundred") are not digits in the text
            modelAmount != null && modelAmount in inText -> modelAmount
            hints != null -> { review = true; hints.amountPaise }
            else -> return null                             // model invented a number and rules found none
        }
        if (amount <= 0) return null

        // ---- direction ----
        val modelDir = j.enumOrNull<Direction>("direction")
        val direction = when {
            modelDir == null -> hints?.direction ?: return null
            hints != null && hints.direction != modelDir && !manual -> {
                review = true
                if (confidence >= 0.8f) modelDir else hints.direction
            }
            else -> modelDir
        }

        val instrument = j.enumOrNull<Instrument>("instrument")?.takeIf { it != Instrument.OTHER }
            ?: hints?.instrument ?: Instrument.OTHER
        val account = j.str("account") ?: hints?.last4
        val counterparty = Names.pretty(j.str("counterparty") ?: hints?.party)
        var partyKind = j.enumOrNull<PartyKind>("counterparty_kind") ?: PartyKind.UNKNOWN
        val reference = j.str("reference")?.takeIf { raw.body.contains(it) } ?: hints?.ref
        val item = j.str("item")

        // ---- category + kind, with consistency rules ----
        var category = j.enumOrNull<Category>("category") ?: Category.OTHER
        if (category == Category.OTHER) category = CategoryHeuristics.guess(raw.body, direction)
        var kind = j.enumOrNull<TxnKind>("kind") ?: TxnKind.NORMAL
        kind = fixKind(kind, direction, partyKind)
        if (partyKind == PartyKind.UNKNOWN && CategoryHeuristics.looksLikePerson(counterparty) &&
            category == Category.OTHER) partyKind = PartyKind.PERSON
        // A payment to/from a person with no stated purpose is never silently "an expense".
        if (!manual && partyKind == PartyKind.PERSON && kind == TxnKind.NORMAL && item == null &&
            category.flow != MoneyFlow.INCOME) {
            kind = TxnKind.PERSON_TRANSFER
        }
        if (kind.isPeopleLedger || kind == TxnKind.PERSON_TRANSFER) category = Category.PEOPLE
        if (kind == TxnKind.PERSON_TRANSFER) review = true
        if (kind == TxnKind.REFUND && category == Category.OTHER) category = Category.REFUND

        // ---- things the user already taught us ----
        Names.key(counterparty)?.let { key ->
            dao.rule(key)?.let { rule ->
                if (kind == TxnKind.NORMAL) {
                    category = runCatching { Category.valueOf(rule.category) }.getOrDefault(category)
                    confidence = 1f
                    review = false
                }
            }
        }

        if (confidence < 0.6f) review = true
        if (manual) review = false

        return ParsedTxn(
            direction = direction,
            amountPaise = amount,
            occurredAt = resolveTime(j.str("date"), raw.receivedAt),
            instrument = instrument,
            accountHint = account,
            counterparty = counterparty,
            partyKind = partyKind,
            platform = j.str("platform"),
            item = item,
            referenceId = reference,
            category = category,
            kind = kind,
            confidence = confidence,
            needsReview = review,
            note = if (manual) raw.body else null,
        )
    }

    /** Safety net: a kind that contradicts the direction of money cannot be right. */
    private fun fixKind(kind: TxnKind, dir: Direction, party: PartyKind): TxnKind {
        val ok = when (kind) {
            TxnKind.LENT, TxnKind.REPAYMENT_MADE -> dir == Direction.DEBIT
            TxnKind.BORROWED, TxnKind.REPAYMENT_RECEIVED, TxnKind.REFUND -> dir == Direction.CREDIT
            else -> true
        }
        return when {
            ok -> kind
            party == PartyKind.PERSON -> TxnKind.PERSON_TRANSFER
            else -> TxnKind.NORMAL
        }
    }

    private fun fromRules(raw: RawMessage, h: RuleHints): ParsedTxn {
        val category = CategoryHeuristics.guess(raw.body, h.direction)
        val person = category == Category.OTHER && CategoryHeuristics.looksLikePerson(h.party)
        val kind = if (person) TxnKind.PERSON_TRANSFER else TxnKind.NORMAL
        return ParsedTxn(
            direction = h.direction,
            amountPaise = h.amountPaise,
            occurredAt = raw.receivedAt,
            instrument = h.instrument,
            accountHint = h.last4,
            counterparty = Names.pretty(h.party),
            partyKind = if (person) PartyKind.PERSON else PartyKind.UNKNOWN,
            platform = null,
            item = null,
            referenceId = h.ref,
            category = if (person) Category.PEOPLE else category,
            kind = kind,
            confidence = 0.5f,
            needsReview = category == Category.OTHER || person,
        )
    }

    private fun resolveTime(date: String?, receivedAt: Long): Long {
        val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return receivedAt
        val zone = ZoneId.systemDefault()
        val received = Instant.ofEpochMilli(receivedAt).atZone(zone).toLocalDate()
        if (d == received || d.isAfter(received.plusDays(1))) return receivedAt // same day, or a bogus future date
        return d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    }
}

// ---- small JSON helpers (org.json returns the string "null" for JSON null, so guard it) ----
internal fun JSONObject.str(key: String): String? {
    if (isNull(key)) return null
    val v = optString(key).trim()
    return v.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}

internal inline fun <reified E : Enum<E>> JSONObject.enumOrNull(key: String): E? =
    str(key)?.let { s -> runCatching { enumValueOf<E>(s.uppercase().replace(' ', '_')) }.getOrNull() }
