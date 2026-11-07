package app.mymemory.ledger.parse

/** Name normalisation shared by grouping (people balances), learned rules and dedup. */
object Names {
    private val STOP = setOf(
        "pay", "india", "pvt", "ltd", "upi", "payments", "bank", "the",
        "limited", "private", "online", "services"
    )
    private val VPA_DOMAIN = Regex("""@.*$""")
    private val SPLIT = Regex("""[^a-z0-9]+""")

    fun tokens(s: String?): Set<String> {
        if (s == null) return emptySet()
        return s.lowercase().replace(VPA_DOMAIN, "").split(SPLIT)
            .filter { it.length > 1 && it !in STOP }
            .toSet()
    }

    /** Stable grouping key: "Priya Mehta" and "MEHTA PRIYA" give the same key. */
    fun key(s: String?): String? = tokens(s).sorted().joinToString(" ").ifBlank { null }

    fun similar(a: String?, b: String?): Boolean? {
        val x = tokens(a)
        val y = tokens(b)
        if (x.isEmpty() || y.isEmpty()) return null // unknown, not "different"
        return x.containsAll(y) || y.containsAll(x) || x.intersect(y).isNotEmpty()
    }

    fun pretty(s: String?): String? {
        val t = s?.trim()?.replace(VPA_DOMAIN, "")?.takeIf { it.isNotBlank() } ?: return null
        return if (t == t.uppercase() || t == t.lowercase())
            t.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        else t
    }
}
