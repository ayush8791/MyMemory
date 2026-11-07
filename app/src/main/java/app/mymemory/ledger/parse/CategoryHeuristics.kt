package app.mymemory.ledger.parse

import app.mymemory.ledger.data.Category
import app.mymemory.ledger.data.Direction

/**
 * Keyword fallback used only when the model is missing, fails, or answers OTHER.
 * Deliberately small: the model does the real categorisation.
 */
object CategoryHeuristics {
    private val rules: List<Pair<Regex, Category>> = listOf(
        Regex("""(?i)credit card (bill|payment)|cc bill|card payment received|billdesk.*card""") to Category.CC_BILL,
        Regex("""(?i)salary|payroll""") to Category.SALARY,
        Regex("""(?i)\binterest\b""") to Category.INTEREST,
        Regex("""(?i)\brefund""") to Category.REFUND,
        Regex("""(?i)cashback""") to Category.CASHBACK,
        Regex("""(?i)\batm\b|cash withdrawal""") to Category.CASH_WITHDRAWAL,
        Regex("""(?i)swiggy|zomato|restaurant|cafe|dominos|mcdonald|kfc|starbucks|pizza|burger|blue tokai|eatfit""") to Category.FOOD,
        Regex("""(?i)bigbasket|blinkit|zepto|instamart|grofers|dmart|grocer|supermarket|reliance fresh""") to Category.GROCERIES,
        Regex("""(?i)uber|\bola\b|rapido|petrol|fuel|irctc|fastag|metro|bpcl|hpcl|indian oil""") to Category.TRANSPORT,
        Regex("""(?i)netflix|spotify|prime video|hotstar|youtube premium|apple\.com/bill|google play|subscription""") to Category.SUBSCRIPTIONS,
        Regex("""(?i)electricity|bescom|msedcl|tneb|water bill|gas|broadband|airtel|\bjio\b|vodafone|\bvi\b|recharge|postpaid""") to Category.BILLS,
        Regex("""(?i)amazon|flipkart|myntra|ajio|nykaa|meesho|croma""") to Category.SHOPPING,
        Regex("""(?i)pharmacy|apollo|medplus|1mg|hospital|clinic|diagnostic|pharmeasy""") to Category.HEALTH,
        Regex("""(?i)makemytrip|goibibo|indigo|air india|booking\.com|airbnb|oyo|cleartrip""") to Category.TRAVEL,
        Regex("""(?i)bookmyshow|pvr|inox|cinema""") to Category.ENTERTAINMENT,
        Regex("""(?i)\bemi\b|loan""") to Category.EMI_LOANS,
        Regex("""(?i)insurance|lic\b|premium""") to Category.INSURANCE,
        Regex("""(?i)zerodha|groww|mutual fund|sip\b|nps\b|upstox|coin by""") to Category.INVESTMENTS,
        Regex("""(?i)\brent\b""") to Category.RENT,
        Regex("""(?i)school|college|tuition|course|udemy|coursera|byju""") to Category.EDUCATION,
        Regex("""(?i)charges|\bfee\b|gst on|annual fee""") to Category.FEES,
    )

    fun guess(text: String, @Suppress("UNUSED_PARAMETER") direction: Direction): Category =
        rules.firstOrNull { it.first.containsMatchIn(text) }?.second ?: Category.OTHER

    /** "Rahul Sharma": 1 to 3 plain alphabetic words, no handle, no digits. */
    fun looksLikePerson(name: String?): Boolean {
        if (name == null || '@' in name) return false
        val words = name.trim().split(Regex("""\s+"""))
        return words.size in 1..3 && words.all { w -> w.length > 1 && w.all { it.isLetter() } }
    }
}
