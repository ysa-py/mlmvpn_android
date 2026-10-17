package com.mlmvpn.scanner.quick

import java.util.Locale

/**
 * Country identity for a proxy node -- flag, ISO code, and a localised name.
 *
 * Direct port of the desktop app's `geo-label.js`. Two things this deliberately does NOT do:
 *
 *  1. It does not geolocate the server's IP. Measured on a 2827-server feed, an IP database
 *     agreed with the provider's own country label only 34% of the time. The disagreements
 *     are not noise: 104.21.x / 104.27.x are Cloudflare anycast (the database says US, the
 *     node exits in Frankfurt), and OVH / Scaleway / Oracle ranges are registered in one
 *     country and served from another. For CDN and hosting addresses the database reports
 *     where the range was REGISTERED, not where it answers.
 *
 *  2. It does not treat its answer as fact. This is the pre-connect guess, drawn from what
 *     the node calls itself. The only authority on where traffic actually leaves is a live
 *     request made through the tunnel -- see [EgressTracer].
 *
 * The name comes from the platform's own region data, so "DE" becomes «آلمان» without a
 * translation table.
 */
data class GeoCountry(
    val code: String,
    val name: String,
    val flag: String,
    /** "flag" | "code" | "name" -- which rule decided, in descending confidence. */
    val source: String = "",
) {
    val label: String get() = "$flag $name"
}

object GeoLabel {

    private const val REGIONAL_A = 0x1F1E6      // regional indicator A
    private const val REGIONAL_Z = 0x1F1FF      // regional indicator Z

    val FA: Locale = Locale("fa")

    /** Codes that appear in node names but are not what the region tables expect. */
    private val ALIASES = mapOf("UK" to "GB", "EN" to "GB", "SU" to "RU")

    /**
     * Words that identify a country when neither a flag nor a code token is present.
     *
     * Two-letter entries are matched as whole tokens only, never as substrings -- otherwise
     * "IR" inside "IRELAND" or "DE" inside "DEDICATED" would decide the country. City names
     * are included where a provider is more likely to name the city than the country.
     */
    private val KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "US" to listOf("us", "usa", "united states", "america", "american", "dallas", "miami", "seattle", "ashburn", "chicago", "phoenix", "atlanta", "losangeles", "newyork"),
        "DE" to listOf("de", "deu", "ger", "germany", "german", "deutschland", "frankfurt", "berlin", "munich", "nuremberg", "falkenstein"),
        "NL" to listOf("nl", "nld", "netherlands", "holland", "dutch", "amsterdam"),
        "GB" to listOf("uk", "gb", "gbr", "united kingdom", "england", "britain", "london", "manchester"),
        "FR" to listOf("fr", "fra", "france", "french", "paris", "marseille", "gravelines"),
        "JP" to listOf("jp", "jpn", "japan", "japanese", "tokyo", "osaka"),
        "SG" to listOf("sg", "sgp", "singapore"),
        "HK" to listOf("hk", "hkg", "hongkong", "hong kong"),
        "TW" to listOf("tw", "twn", "taiwan", "taipei"),
        "KR" to listOf("kr", "kor", "korea", "seoul"),
        "CA" to listOf("ca", "can", "canada", "toronto", "montreal", "beauharnois"),
        "AU" to listOf("au", "aus", "australia", "sydney", "melbourne"),
        "TR" to listOf("tr", "tur", "turkey", "turkiye", "istanbul"),
        "RU" to listOf("ru", "rus", "russia", "moscow"),
        "PL" to listOf("pl", "pol", "poland", "warsaw"),
        "IT" to listOf("it", "ita", "italy", "milan", "rome"),
        "ES" to listOf("es", "esp", "spain", "madrid", "barcelona"),
        "SE" to listOf("se", "swe", "sweden", "stockholm"),
        "FI" to listOf("fi", "fin", "finland", "helsinki"),
        "NO" to listOf("no", "nor", "norway", "oslo"),
        "DK" to listOf("dk", "dnk", "denmark", "copenhagen"),
        "IE" to listOf("ie", "irl", "ireland", "dublin"),
        "CH" to listOf("ch", "che", "switzerland", "zurich", "geneva"),
        "AT" to listOf("at", "aut", "austria", "vienna"),
        "BE" to listOf("be", "bel", "belgium", "brussels"),
        "CZ" to listOf("cz", "cze", "czech", "czechia", "prague"),
        "RO" to listOf("ro", "rou", "romania", "bucharest"),
        "BG" to listOf("bg", "bgr", "bulgaria", "sofia"),
        "HU" to listOf("hu", "hun", "hungary", "budapest"),
        "LT" to listOf("lt", "ltu", "lithuania", "vilnius"),
        "LV" to listOf("lv", "lva", "latvia", "riga"),
        "EE" to listOf("ee", "est", "estonia", "tallinn"),
        "UA" to listOf("ua", "ukr", "ukraine", "kyiv", "kiev"),
        "RS" to listOf("rs", "srb", "serbia", "belgrade"),
        "MD" to listOf("md", "mda", "moldova"),
        "BY" to listOf("by", "blr", "belarus"),
        "KZ" to listOf("kz", "kaz", "kazakhstan", "almaty"),
        "AM" to listOf("am", "arm", "armenia", "yerevan"),
        "GE" to listOf("ge", "geo", "georgia", "tbilisi"),
        "AZ" to listOf("az", "aze", "azerbaijan", "baku"),
        "AE" to listOf("ae", "are", "uae", "emirates", "dubai", "abudhabi"),
        "SA" to listOf("sa", "sau", "saudi", "saudiarabia", "riyadh"),
        "QA" to listOf("qa", "qat", "qatar", "doha"),
        "KW" to listOf("kw", "kwt", "kuwait"),
        "OM" to listOf("om", "omn", "oman", "muscat"),
        "IL" to listOf("il", "isr", "israel", "telaviv"),
        "IN" to listOf("in", "ind", "india", "mumbai", "delhi", "bangalore"),
        "ID" to listOf("id", "idn", "indonesia", "jakarta"),
        "MY" to listOf("my", "mys", "malaysia", "kualalumpur"),
        "TH" to listOf("th", "tha", "thailand", "bangkok"),
        "VN" to listOf("vn", "vnm", "vietnam", "hanoi", "saigon"),
        "PH" to listOf("ph", "phl", "philippines", "manila"),
        "CN" to listOf("cn", "chn", "china", "shanghai", "beijing", "shenzhen"),
        "BR" to listOf("br", "bra", "brazil", "saopaulo"),
        "AR" to listOf("ar", "arg", "argentina", "buenosaires"),
        "CL" to listOf("cl", "chl", "chile", "santiago"),
        "CO" to listOf("co", "col", "colombia", "bogota"),
        "PE" to listOf("pe", "per", "peru", "lima"),
        "MX" to listOf("mx", "mex", "mexico"),
        "PA" to listOf("pa", "pan", "panama"),
        "ZA" to listOf("za", "zaf", "southafrica", "johannesburg"),
        "NG" to listOf("ng", "nga", "nigeria", "lagos"),
        "EG" to listOf("eg", "egy", "egypt", "cairo"),
        "NZ" to listOf("nz", "nzl", "newzealand", "auckland"),
        "PT" to listOf("pt", "prt", "portugal", "lisbon"),
        "GR" to listOf("gr", "grc", "greece", "athens"),
        "HR" to listOf("hr", "hrv", "croatia", "zagreb"),
        "SK" to listOf("sk", "svk", "slovakia", "bratislava"),
        "SI" to listOf("si", "svn", "slovenia", "ljubljana"),
        "AL" to listOf("al", "alb", "albania", "tirana"),
        "CY" to listOf("cy", "cyp", "cyprus", "nicosia"),
        "IS" to listOf("is", "isl", "iceland", "reykjavik"),
        "LU" to listOf("lu", "lux", "luxembourg"),
        "MT" to listOf("mt", "mlt", "malta"),
        "IR" to listOf("ir", "irn", "iran", "tehran"),
    )

    // A two-letter code is only decisive as its own token; a longer word may also match a run
    // of letters inside a larger name ("frankfurt" inside "de-frankfurt-01").
    private val SHORT_TOKENS: Map<String, String>
    private val LONG_TOKENS: List<Pair<String, String>>

    init {
        val shorts = LinkedHashMap<String, String>()
        val longs = ArrayList<Pair<String, String>>()
        for ((code, words) in KEYWORDS) {
            for (w in words) {
                if (w.length <= 3) {
                    if (!shorts.containsKey(w)) shorts[w] = code
                } else {
                    longs.add(w.replace(Regex("\\s+"), "") to code)
                }
            }
        }
        // Longest first, so a shorter word that happens to be a prefix cannot win.
        longs.sortByDescending { it.first.length }
        SHORT_TOKENS = shorts
        LONG_TOKENS = longs
    }

    /** Resolved names are stable per (code, locale) and this is called once per row. */
    private val nameCache = HashMap<String, String?>()

    /**
     * "DE" -> the German flag. The flag is BUILT from the code, never copied out of the node's
     * name, so a name carrying the wrong flag next to the right code cannot produce a
     * mismatched pair on screen.
     */
    fun flagForCode(code: String?): String {
        if (code == null || code.length != 2) return "🏳️"
        return String(
            intArrayOf(
                REGIONAL_A + (code[0].code - 'A'.code),
                REGIONAL_A + (code[1].code - 'A'.code),
            ), 0, 2
        )
    }

    fun normalizeCode(raw: String?): String? {
        val code = raw?.trim()?.uppercase(Locale.US) ?: return null
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return null
        return ALIASES[code] ?: code
    }

    /** Full identity for an ISO code, or null when the code is not a real region. */
    @Synchronized
    fun countryFromCode(raw: String?, locale: Locale = FA): GeoCountry? {
        val code = normalizeCode(raw) ?: return null
        val key = code + "|" + locale.language
        val name = if (nameCache.containsKey(key)) {
            nameCache[key]
        } else {
            val resolved = resolveName(code, locale)
            nameCache[key] = resolved
            resolved
        } ?: return null
        return GeoCountry(code = code, name = name, flag = flagForCode(code))
    }

    private fun resolveName(code: String, locale: Locale): String? {
        val got = try {
            Locale.Builder().setRegion(code).build().getDisplayCountry(locale)
        } catch (e: Exception) {
            return null
        }
        // The platform echoes the input back for codes it does not know -- treat that as a miss
        // rather than showing the user a bare two-letter label as if it were a country.
        if (got.isNullOrBlank() || got == code) return null
        // A few regions come back as administrative full names -- Hong Kong is «هنگ‌کنگ، منطقهٔ
        // ویژهٔ اداری چین», four times the width of every other row in a flag list. The qualifier
        // always follows a comma, so dropping it leaves the name people actually use without a
        // per-country translation table.
        return got.split(',', '،')[0].trim().ifBlank { got }
    }

    /** The first regional-indicator pair anywhere in the text, as an ISO code. */
    private fun flagCodeIn(text: String): String? {
        val points = text.codePoints().toArray()
        for (i in 0 until points.size - 1) {
            if (points[i] in REGIONAL_A..REGIONAL_Z && points[i + 1] in REGIONAL_A..REGIONAL_Z) {
                return String(
                    charArrayOf(
                        (points[i] - REGIONAL_A + 65).toChar(),
                        (points[i + 1] - REGIONAL_A + 65).toChar(),
                    )
                )
            }
        }
        return null
    }

    private val CODE_TOKEN = Regex("^([A-Za-z]{2})\\d+")

    /**
     * The "DE1" / "NL12" convention: a pipe-separated field that is a country code followed by
     * an index. Common enough in public feeds to be worth a rule of its own, and it survives
     * clients that strip emoji.
     */
    private fun codeTokenIn(text: String): String? {
        for (part in text.split('|')) {
            val m = CODE_TOKEN.find(part.trim()) ?: continue
            normalizeCode(m.groupValues[1])?.let { return it }
        }
        return null
    }

    private fun keywordCodeIn(text: String): String? {
        val flat = text.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim()
        if (flat.isEmpty()) return null
        val squashed = flat.replace(Regex("\\s+"), "")
        for ((word, code) in LONG_TOKENS) {
            if (squashed.contains(word)) return code
        }
        for (token in flat.split(' ')) {
            SHORT_TOKENS[token]?.let { return it }
        }
        return null
    }

    /**
     * Country for a node, from whatever text describes it. Order is confidence order: an
     * explicit flag beats a code token, which beats a guess from words.
     *
     * Returns null when nothing identifies it -- null is a real answer here, and the UI groups
     * those under «نامشخص» rather than inventing a country for them.
     */
    fun countryFromText(parts: List<String?>, locale: Locale = FA): GeoCountry? {
        val text = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
        if (text.isBlank()) return null

        countryFromCode(flagCodeIn(text), locale)?.let { return it.copy(source = "flag") }
        countryFromCode(codeTokenIn(text), locale)?.let { return it.copy(source = "code") }
        countryFromCode(keywordCodeIn(text), locale)?.let { return it.copy(source = "name") }
        return null
    }
}
