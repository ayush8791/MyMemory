package app.mymemory

import app.mymemory.ledger.Balances
import app.mymemory.ledger.Deduper
import app.mymemory.ledger.Deduper.View
import app.mymemory.ledger.data.Direction
import app.mymemory.ledger.data.Instrument
import app.mymemory.ledger.data.LedgerTxn
import app.mymemory.ledger.parse.RuleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases the rule parser and dedup scorer were first validated against in a JVM prototype. */
class LogicTest {

    // ---------------- rule parser ----------------
    @Test fun upiToMerchant() {
        val h = RuleParser.parse("Rs.1,250.00 debited from a/c **1234 on 05-11-26 to VPA zomato@icici(UPI Ref No 431234567890). Not you? Call 1800")!!
        assertEquals(Direction.DEBIT, h.direction)
        assertEquals(125_000L, h.amountPaise)
        assertEquals("1234", h.last4)
        assertEquals("431234567890", h.ref)
        assertEquals(Instrument.UPI, h.instrument)
    }

    @Test fun cardSpendIgnoresLimit() {
        val h = RuleParser.parse("Thank you for using your ICICI Bank Credit Card XX4321 for INR 2,499.00 at AMAZON PAY INDIA on 04-Nov-26. Avl Limit: INR 1,20,000.00")!!
        assertEquals(249_900L, h.amountPaise)
        assertEquals(Instrument.CREDIT_CARD, h.instrument)
        assertEquals("4321", h.last4)
    }

    @Test fun salaryIgnoresBalance() {
        val h = RuleParser.parse("INR 85,000.00 credited to your A/c XX9876 on 01-11-2026 by NEFT-ACME CORP-SALARY. Avl Bal INR 1,02,345.67")!!
        assertEquals(Direction.CREDIT, h.direction)
        assertEquals(8_500_000L, h.amountPaise)
    }

    @Test fun creditNamesTheSender() {
        val h = RuleParser.parse("Your SBI A/c X5678 credited by Rs.2000.0 on 03Nov26 transfer from PRIYA MEHTA Ref No 431234500001")!!
        assertEquals(200_000L, h.amountPaise)
        assertEquals("PRIYA MEHTA", h.party)
    }

    @Test fun merchantNameContainingMinDoesNotHideAmount() {
        val h = RuleParser.parse("Thank you for using your card XX1111 at DOMINOS for Rs 300. Ref 123456789012")!!
        assertEquals(30_000L, h.amountPaise)
    }

    @Test fun nonTransactionsAreRejected() {
        assertNull(RuleParser.parse("123456 is your OTP for transaction of Rs 999 at Flipkart. Do not share."))
        assertNull(RuleParser.parse("Get 20% cashback up to Rs 100 on recharge. Use code SAVE20"))
        assertNull(RuleParser.parse("Your A/c XX1234 Avl Bal is Rs 5,000"))
    }

    @Test fun atmAndRefund() {
        assertEquals(Instrument.CASH, RuleParser.parse("Rs 5,000.00 withdrawn from ATM on 02-11-26 using card ending 4321")!!.instrument)
        assertEquals(Direction.CREDIT, RuleParser.parse("Refund of Rs 799.00 for your order from MYNTRA has been credited to your card ending 4321")!!.direction)
    }

    // ---------------- dedup ----------------
    private val t0 = 1_000_000_000_000L
    private val min = 60_000L

    private fun v(paise: Long = 125_000, dir: String = "DEBIT", dt: Long = 0, ref: String? = null, last4: String? = null,
                  party: String? = null, platform: String? = null, ch: String) =
        View(dir, paise, t0 + dt, ref, last4, party, platform, "UPI", setOf(ch))

    @Test fun smsPlusEmailReceiptMerge() {
        val bank = v(ref = "431234567890", last4 = "1234", party = "zomato@icici", ch = "SMS:hdfcbk")
        val mail = v(dt = 3 * min, party = "Zomato", platform = "Zomato", ch = "GMAIL:noreply@zomato.com")
        assertTrue(Deduper.score(mail, bank) >= Deduper.THRESHOLD)
    }

    @Test fun bankSmsPlusUpiAppSmsMerge() {
        val a = v(paise = 50_000, party = "Rahul Sharma", ch = "SMS:hdfcbk")
        val b = v(paise = 50_000, dt = min, party = "RAHUL SHARMA", ch = "SMS:phonepe")
        assertTrue(Deduper.score(b, a) >= Deduper.THRESHOLD)
    }

    @Test fun twoRealCoffeesStaySeparate() {
        val withRefs = Deduper.score(
            v(paise = 12_000, dt = 5 * min, ref = "222222222222", party = "Blue Tokai", ch = "SMS:hdfcbk"),
            v(paise = 12_000, ref = "111111111111", party = "Blue Tokai", ch = "SMS:hdfcbk"))
        assertEquals(Deduper.NEVER, withRefs)
        val noRefs = Deduper.score(
            v(paise = 12_000, dt = 5 * min, party = "Blue Tokai", ch = "SMS:hdfcbk"),
            v(paise = 12_000, party = "Blue Tokai", ch = "SMS:hdfcbk"))
        assertTrue(noRefs < Deduper.THRESHOLD)
    }

    @Test fun sameAmountDifferentPeopleStaySeparate() {
        val s = Deduper.score(
            v(paise = 50_000, dt = 5 * min, party = "Priya", ch = "SMS:phonepe"),
            v(paise = 50_000, party = "Rahul", ch = "SMS:hdfcbk"))
        assertTrue(s < Deduper.THRESHOLD)
    }

    @Test fun cardSmsPlusNextDayEmailMerge() {
        val sms = v(paise = 249_900, last4 = "4321", party = "AMAZON PAY INDIA", ch = "SMS:icicib")
        val mail = v(paise = 249_900, dt = 20 * 3_600_000L, party = "Amazon", platform = "Amazon", ch = "GMAIL:auto-confirm@amazon.in")
        assertTrue(Deduper.score(mail, sms) >= Deduper.THRESHOLD)
    }

    @Test fun differentAccountOrDirectionNeverMerge() {
        val a = v(last4 = "1234", party = "x", ch = "SMS:a")
        assertEquals(Deduper.NEVER, Deduper.score(v(dt = min, last4 = "9999", party = "x", ch = "SMS:b"), a))
        assertEquals(Deduper.NEVER, Deduper.score(v(dir = "CREDIT", dt = min, party = "x", ch = "SMS:b"), a))
    }

    // ---------------- people balances ----------------
    private fun txn(kind: String, dir: String, paise: Long, name: String, at: Long = t0) = LedgerTxn(
        direction = dir, amountPaise = paise, occurredAt = at, instrument = "UPI", accountHint = null,
        counterparty = name, counterpartyKey = name.lowercase(), partyKind = "PERSON", platform = null, item = null,
        referenceId = null, category = "PEOPLE", kind = kind, confidence = 1f, needsReview = false,
        channels = "MANUAL:me", createdAt = at,
    )

    @Test fun lendAndPartialRepayment() {
        val rows = listOf(
            txn("LENT", "DEBIT", 200_000, "Priya"),
            txn("REPAYMENT_RECEIVED", "CREDIT", 50_000, "Priya", t0 + 1),
            txn("BORROWED", "CREDIT", 30_000, "Rahul"),
        )
        val people = Balances.people(rows).associateBy { it.key }
        assertEquals(150_000L, people["priya"]!!.netPaise)   // Priya still owes 1,500
        assertEquals(-30_000L, people["rahul"]!!.netPaise)   // you owe Rahul 300
        assertNotNull(people["priya"])
    }
}
