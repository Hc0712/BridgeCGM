package tw.yourcompany.cgmbridge.feature.input.notification

/**
 * Strips arrows, unit text, and special whitespace so that a bare glucose
 * number can be parsed. Mirrors xDrip+ UiBasedCollector filter pipeline.
 */
object CgmStringFilter {

    /** Full filter: arrows → units → trim. */
    fun filter(value: String): String = basicFilter(arrowFilter(value)).trim()

    /** Removes unit strings, non-breaking spaces, word joiners. */
    fun basicFilter(value: String): String = value
        .replace("\u00a0", " ")
        .replace("\u2060", "")
        .replace("\\", "/")
        .replace("mmol/L", "", ignoreCase = true)
        .replace("mg/dL", "", ignoreCase = true)
        .replace("mgdl", "", ignoreCase = true)
        .replace("mmoll", "", ignoreCase = true)
        .replace("←", "")
        .replace("→", "")

    /** Removes Unicode arrow ranges (\u2190–\u21FF, \u2700–\u27BF, \u2900–\u297F, \u2B00–\u2BFF). */
    fun arrowFilter(value: String): String {
        val sb = StringBuilder(value.length)
        for (c in value) {
            if (c in '\u2190'..'\u21FF' || c in '\u2700'..'\u27BF' ||
                c in '\u2900'..'\u297F' || c in '\u2B00'..'\u2BFF') continue
            sb.append(c)
        }
        return sb.toString()
    }

    /** True when text looks like a valid mmol/L value (e.g. "5.6" or "5,6"). */
    fun isValidMmol(text: String): Boolean = text.matches(Regex("[0-9]+[.,][0-9]+"))
}

