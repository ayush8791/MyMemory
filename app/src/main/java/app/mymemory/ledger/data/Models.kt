package app.mymemory.ledger.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class Direction { DEBIT, CREDIT }

enum class Instrument { CREDIT_CARD, DEBIT_CARD, UPI, NETBANKING, CASH, WALLET, OTHER }

enum class PartyKind { MERCHANT, PERSON, SELF, BANK, UNKNOWN }

/**
 * NORMAL            ordinary purchase / income
 * PERSON_TRANSFER   money to/from an individual, purpose not known yet (goes to review)
 * LENT / BORROWED   you gave / received money that has to come back
 * REPAYMENT_*       the money coming back (RECEIVED = they repaid you, MADE = you repaid them)
 */
enum class TxnKind(val label: String) {
    NORMAL("Normal"),
    PERSON_TRANSFER("Person transfer"),
    LENT("Lent"),
    BORROWED("Borrowed"),
    REPAYMENT_RECEIVED("They repaid me"),
    REPAYMENT_MADE("I repaid them"),
    REFUND("Refund");

    val isPeopleLedger get() = this == LENT || this == BORROWED || this == REPAYMENT_RECEIVED || this == REPAYMENT_MADE
}

enum class MoneyFlow { SPEND, INCOME, REFUND, NEUTRAL }

enum class Category(val label: String, val flow: MoneyFlow) {
    FOOD("Food & dining", MoneyFlow.SPEND),
    GROCERIES("Groceries", MoneyFlow.SPEND),
    SHOPPING("Shopping", MoneyFlow.SPEND),
    TRANSPORT("Transport & fuel", MoneyFlow.SPEND),
    BILLS("Bills & utilities", MoneyFlow.SPEND),
    SUBSCRIPTIONS("Subscriptions", MoneyFlow.SPEND),
    ENTERTAINMENT("Entertainment", MoneyFlow.SPEND),
    HEALTH("Health", MoneyFlow.SPEND),
    TRAVEL("Travel", MoneyFlow.SPEND),
    EDUCATION("Education", MoneyFlow.SPEND),
    RENT("Rent", MoneyFlow.SPEND),
    EMI_LOANS("EMI & loans", MoneyFlow.SPEND),
    INSURANCE("Insurance", MoneyFlow.SPEND),
    FEES("Fees & charges", MoneyFlow.SPEND),
    CASH_WITHDRAWAL("Cash withdrawal", MoneyFlow.SPEND),
    SALARY("Salary", MoneyFlow.INCOME),
    INTEREST("Interest", MoneyFlow.INCOME),
    REFUND("Refund", MoneyFlow.REFUND),
    CASHBACK("Cashback", MoneyFlow.REFUND),
    INVESTMENTS("Investments", MoneyFlow.NEUTRAL),
    SELF_TRANSFER("Own-account transfer", MoneyFlow.NEUTRAL),
    CC_BILL("Credit card bill payment", MoneyFlow.NEUTRAL),
    PEOPLE("Money with people", MoneyFlow.NEUTRAL),
    OTHER("Other", MoneyFlow.SPEND);
}

enum class RawStatus { PENDING, PARSED, SKIPPED, FAILED }

/** One SMS / email / spoken note exactly as received. Kept so parsing can be re-run later. */
@Entity(
    tableName = "raw",
    indices = [Index(value = ["source", "externalId"], unique = true), Index("status")]
)
data class RawMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,            // SMS | GMAIL | MANUAL
    val externalId: String,        // SMS _id / Gmail message id / uuid
    val sender: String,
    val receivedAt: Long,
    val body: String,
    val status: String = RawStatus.PENDING.name,
    val txnId: Long? = null,
    val attempts: Int = 0,
)

/** The canonical ledger row. Several RawMessages can point at one of these (that is the dedup). */
@Entity(
    tableName = "txn",
    indices = [Index("occurredAt"), Index("counterpartyKey"), Index(value = ["direction", "amountPaise"])]
)
data class LedgerTxn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val direction: String,
    val amountPaise: Long,                 // integer paise: no floating point money
    val occurredAt: Long,
    val instrument: String,
    val accountHint: String?,              // last 4 digits or bank name
    val counterparty: String?,
    val counterpartyKey: String?,          // normalised name, used to group people / merchants
    val partyKind: String,
    val platform: String?,                 // Amazon, Swiggy, ...
    val item: String?,
    val referenceId: String?,              // UPI ref / UTR / RRN
    val category: String,
    val kind: String,
    val confidence: Float,
    val needsReview: Boolean,
    val userEdited: Boolean = false,
    val note: String? = null,
    val channels: String,                  // comma separated "SMS:HDFCBK,GMAIL:orders@x.com"
    val createdAt: Long,
) {
    val dir: Direction get() = Direction.valueOf(direction)
    val cat: Category get() = runCatching { Category.valueOf(category) }.getOrDefault(Category.OTHER)
    val txnKind: TxnKind get() = runCatching { TxnKind.valueOf(kind) }.getOrDefault(TxnKind.NORMAL)
    val party: PartyKind get() = runCatching { PartyKind.valueOf(partyKind) }.getOrDefault(PartyKind.UNKNOWN)
}

/** Learned from the user's corrections: "Blue Tokai is always Food". */
@Entity(tableName = "merchant_rule")
data class MerchantRule(
    @PrimaryKey val ruleKey: String,
    val category: String,
)
