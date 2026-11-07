package app.mymemory.ledger.parse

import app.mymemory.ledger.data.Category
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The single prompt that turns a messy SMS / email / dictated note into one structured record. */
object Prompt {
    private val categories = Category.entries.joinToString(" | ") { it.name }

    private val instructions = """
You extract money transactions from bank SMS, emails and spoken notes for a personal ledger in India (INR).
Reply with ONE JSON object and nothing else.

Fields:
- is_transaction: true only if money really moved. false for OTPs, offers, reminders, bill-due notices, balance alerts.
- direction: "DEBIT" (money left the user) or "CREDIT" (money came to the user)
- amount: rupees as a number, copied from the text. Never the balance, limit or a cashback offer.
- date: "YYYY-MM-DD" if the text states the transaction date, else null
- instrument: CREDIT_CARD | DEBIT_CARD | UPI | NETBANKING | CASH | WALLET | OTHER
- account: last 4 digits of the account/card, else null
- counterparty: merchant or person paid / who paid the user, cleaned and in Title Case. null if unknown
- counterparty_kind: MERCHANT | PERSON | SELF | BANK | UNKNOWN  (PERSON = an individual's name)
- platform: shop or app where it was bought (Amazon, Swiggy ...), else null
- item: what was bought, only if the text says so, else null
- reference: UPI / UTR / RRN number, else null
- category: $categories
- kind: NORMAL | PERSON_TRANSFER | LENT | BORROWED | REPAYMENT_RECEIVED | REPAYMENT_MADE | REFUND
  PERSON_TRANSFER = money to or from an individual and the purpose is NOT stated.
  Use LENT / BORROWED / REPAYMENT_* only when the text says so ("lent", "gave back", "returned", "borrowed").
  Paying your own credit card bill is category CC_BILL. Moving money between your own accounts is SELF_TRANSFER.
- confidence: 0 to 1

Examples:
Message: Rs.1,250.00 debited from a/c **1234 on 05-11-26 to VPA zomato@icici(UPI Ref No 431234567890).
{"is_transaction":true,"direction":"DEBIT","amount":1250.00,"date":"2026-11-05","instrument":"UPI","account":"1234","counterparty":"Zomato","counterparty_kind":"MERCHANT","platform":"Zomato","item":null,"reference":"431234567890","category":"FOOD","kind":"NORMAL","confidence":0.95}

Message: Sent Rs.500.00 from HDFC Bank A/c *1234 To RAHUL SHARMA On 05/11/26 Ref 431234567891
{"is_transaction":true,"direction":"DEBIT","amount":500.00,"date":"2026-11-05","instrument":"UPI","account":"1234","counterparty":"Rahul Sharma","counterparty_kind":"PERSON","platform":null,"item":null,"reference":"431234567891","category":"PEOPLE","kind":"PERSON_TRANSFER","confidence":0.9}

Message: Thank you for using your ICICI Bank Credit Card XX4321 for INR 2,499.00 at AMAZON PAY INDIA on 04-Nov-26. Avl Limit: INR 1,20,000.00
{"is_transaction":true,"direction":"DEBIT","amount":2499.00,"date":"2026-11-04","instrument":"CREDIT_CARD","account":"4321","counterparty":"Amazon","counterparty_kind":"MERCHANT","platform":"Amazon","item":null,"reference":null,"category":"SHOPPING","kind":"NORMAL","confidence":0.93}

Message: 482913 is your OTP for transaction of Rs 999 at Flipkart. Do not share.
{"is_transaction":false}

Message: I lent 2000 to Priya for her cab
{"is_transaction":true,"direction":"DEBIT","amount":2000,"date":null,"instrument":"CASH","account":null,"counterparty":"Priya","counterparty_kind":"PERSON","platform":null,"item":"cab fare","reference":null,"category":"PEOPLE","kind":"LENT","confidence":0.9}
""".trimIndent()

    fun build(body: String, sender: String, receivedAt: Long, hints: RuleHints?, manual: Boolean): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(receivedAt))
        val hintLine = hints?.let {
            "Rule-based hints (may be wrong, verify against the text): amount=${it.amountPaise / 100.0}, direction=${it.direction}, " +
                "account=${it.last4}, reference=${it.ref}\n"
        }.orEmpty()
        val kindOfInput = if (manual) "Spoken/typed note from the user" else "Message from \"$sender\""
        return buildString {
            append(instructions).append("\n\n")
            append("Today is ").append(day).append(". Resolve relative dates like \"yesterday\" from today.\n")
            append(hintLine)
            append("Now extract this one.\n")
            append(kindOfInput).append(":\nMessage: ").append(body.take(1800)).append("\n")
        }
    }

    fun extractJson(reply: String): JSONObject? {
        val a = reply.indexOf('{')
        val b = reply.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching { JSONObject(reply.substring(a, b + 1)) }.getOrNull()
    }
}
