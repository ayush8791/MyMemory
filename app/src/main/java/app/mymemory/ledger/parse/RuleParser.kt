package app.mymemory.ledger.parse

import app.mymemory.ledger.data.Direction
import app.mymemory.ledger.data.Instrument

/** What cheap regex rules could read from a message. Used as a gate, as hints for the model, and as a safety net. */
data class RuleHints(
    val direction: Direction,
    val amountPaise: Long,
    val last4: String?,
    val ref: String?,
    val instrument: Instrument,
    val party: String?,
)

/**
 * Patterns here were exercised against realistic Indian bank / UPI / card / salary / ATM / refund
 * messages (including OTP, promo and balance-only messages that must be rejected) before porting.
 */
object RuleParser {
    private val AMOUNT = Regex("""(?i)(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val ANY_NUMBER = Regex("""[0-9][0-9,]*(?:\.[0-9]{1,2})?""")
    private val BAL_BEFORE = Regex("""(?i)\b(bal(?:ance)?|limit|avl|available|outstanding|due|min(?:imum)?)\b[^0-9]{0,15}$""")
    private val DEBIT = Regex("""(?i)\b(debited|spent|purchase|withdrawn|paid|payment of|sent|transferred to|deducted|thank you for using)\b""")
    private val CREDIT = Regex("""(?i)\b(credited|received|deposited|refund(?:ed)?|salary)\b""")
    private val STRONG = Regex("""(?i)\b(debited|credited|spent|withdrawn|received|paid|deducted|deposited)\b""")
    private val OTP = Regex("""(?i)\b(otp|one[- ]time password|verification code)\b""")
    private val PROMO = Regex("""(?i)\b(cashback up to|apply now|pre-?approved|use code|coupon|loan up to|limited period|offer valid)\b""")
    private val LAST4 = Regex("""(?i)(?:a/?c|acct?|account|card)\s*(?:no\.?|number)?\s*(?:ending(?: with)?)?\s*[x*.\s-]*(\d{4})\b""")
    private val REF = Regex("""(?i)(?:upi\s*ref(?:erence)?\s*(?:no\.?)?|utr|rrn|ref(?:erence)?\s*(?:no\.?|number|id)?|txn\s*(?:id|no\.?)?|transaction\s*id)\s*[:#-]?\s*(\d{9,18}|[A-Za-z]{4}\d{8,20})""")
    private val VPA = Regex("""(?i)\b([a-z0-9.\-_]{2,}@[a-z]{2,})\b""")
    private val PARTY = Regex("""(?i)(?:\bat|\bto|\bfrom|\bby|\btowards)\s+(?:vpa\s+)?([A-Za-z][A-Za-z0-9@.&' _-]{1,38}?)(?=\s+(?:on|ref|via|using|dated|txn|upi|avl|for|is|has)\b|\s*[.,(]|$)""")
    private val NOT_A_NAME = Regex("""(?i)(your|a/c|account|card|you|the|rs|inr|atm)\b.*""")
    private val NETBANKING = Regex("""\b(neft|imps|rtgs|netbanking)\b""")

    fun hasAmount(text: String) = AMOUNT.containsMatchIn(text)

    /** OTP-style message with no sign that money actually moved. */
    fun isOtp(text: String) = OTP.containsMatchIn(text) && !STRONG.containsMatchIn(text)

    /** Every number in the text, in paise. A model-supplied amount must be one of these. */
    fun allAmounts(text: String): Set<Long> =
        ANY_NUMBER.findAll(text).mapNotNull { toPaise(it.value) }.toSet()

    private fun toPaise(s: String): Long? =
        s.replace(",", "").toDoubleOrNull()?.let { Math.round(it * 100) }

    fun parse(text: String): RuleHints? {
        val strong = STRONG.containsMatchIn(text)
        if (OTP.containsMatchIn(text) && !strong) return null
        if (PROMO.containsMatchIn(text) && !strong) return null

        val di = DEBIT.find(text)?.range?.first ?: Int.MAX_VALUE
        val ci = CREDIT.find(text)?.range?.first ?: Int.MAX_VALUE
        if (di == Int.MAX_VALUE && ci == Int.MAX_VALUE) return null
        val direction = if (di < ci) Direction.DEBIT else Direction.CREDIT

        // First amount that is not a balance / limit figure.
        val amount = AMOUNT.findAll(text).firstNotNullOfOrNull { m ->
            val before = text.substring(maxOf(0, m.range.first - 25), m.range.first)
            if (BAL_BEFORE.containsMatchIn(before)) null else toPaise(m.groupValues[1])
        } ?: return null

        val lower = text.lowercase()
        val instrument = when {
            "credit card" in lower -> Instrument.CREDIT_CARD
            "debit card" in lower -> Instrument.DEBIT_CARD
            "upi" in lower || "vpa" in lower -> Instrument.UPI
            NETBANKING.containsMatchIn(lower) -> Instrument.NETBANKING
            "atm" in lower -> Instrument.CASH
            else -> Instrument.OTHER
        }

        val party = VPA.find(text)?.groupValues?.get(1)
            ?: PARTY.findAll(text).map { it.groupValues[1].trim() }.firstOrNull { !NOT_A_NAME.matches(it) }

        return RuleHints(
            direction = direction,
            amountPaise = amount,
            last4 = LAST4.find(text)?.groupValues?.get(1),
            ref = REF.find(text)?.groupValues?.get(1),
            instrument = instrument,
            party = party,
        )
    }
}
