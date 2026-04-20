package tw.yourcompany.cgmbridge.feature.input.notification

import tw.yourcompany.cgmbridge.core.model.GlucoseSample
import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace

/**
 * Parses CGM notification text into a [GlucoseSample].
 *
 * Two data paths (matching xDrip+ UiBasedCollector):
 *   1. **Primary** — contentView texts via RemoteViews inflation.
 *   2. **Fallback** — standard notification extras (EXTRA_TITLE / EXTRA_TEXT …).
 *
 * Verbose debug output (all PARSER-V-* tags) is controlled by the verbose flag in [DebugTrace]
 * which maps to cgmbridge.verboseDump in gradle.properties.
 */
class GenericCgmNotificationParser {

    private val reMgdl = Regex("(\\b\\d{2,3}\\b)\\s*(mg\\s*/\\s*d[l1]|mg/dl|mgdl)", RegexOption.IGNORE_CASE)
    private val reMmol = Regex("(\\b\\d{1,2}(?:[.,]\\d{1,2})?)\\s*(mmol\\s*/\\s*l|mmol/l|mmoll)", RegexOption.IGNORE_CASE)
    private val reTrend = Regex("(↑↑|↑|↗|→|↘|↓↓|↓|DoubleUp|SingleUp|FortyFiveUp|Flat|FortyFiveDown|SingleDown|DoubleDown)", RegexOption.IGNORE_CASE)
    private val reStatus = Regex("(signal loss|no signal|sensor error|calibration|warmup|expired|replace sensor)", RegexOption.IGNORE_CASE)
    private val reAlert = Regex("(urgent low|urgent high|low|high|alarm|alert)", RegexOption.IGNORE_CASE)
    private val reBareInt = Regex("^-?\\d{2,3}$")

    fun parse(input: NotificationParseInput): GlucoseSample? {
        val extrasTokens = listOfNotNull(input.title, input.text, input.subText, input.bigText, input.textLines, input.tickerText)
        val extrasCombined = extrasTokens.joinToString(" ").trim()

        DebugTrace.v(DebugCategory.PARSING, "PARSER-V-INPUT") {
            "pkg=${input.sourcePackage} " +
            "cvTexts(${input.contentViewTexts.size})=${input.contentViewTexts} " +
            "extrasCombined=[$extrasCombined] " +
            "postTimeMs=${input.postTimeMs}"
        }

        val timestamp = NotificationTimestampExtractor.extractEpochMs(
            extrasCombined.ifBlank { input.title ?: input.text },
            input.postTimeMs
        )

        DebugTrace.v(DebugCategory.PARSING, "PARSER-V-TS") { "resolvedTimestampMs=$timestamp (fallback=${input.postTimeMs})" }

        // === PRIMARY PATH — contentView texts ===
        if (input.contentViewTexts.isNotEmpty()) {
            DebugTrace.v(DebugCategory.PARSING, "PARSER-V-PATH") { "Trying PRIMARY path (contentViewTexts)" }
            var matches = 0
            var mgdl = 0
            for (raw in input.contentViewTexts) {
                val filtered = CgmStringFilter.filter(raw)
                val parsed = tryExtractFiltered(filtered)
                DebugTrace.v(DebugCategory.PARSING, "PARSER-V-CV") {
                    "raw=[$raw] filtered=[$filtered] parsedMgdl=$parsed"
                }
                if (parsed > 0) { mgdl = parsed; matches++ }
            }
            DebugTrace.v(DebugCategory.PARSING, "PARSER-V-CV-RESULT") {
                "PRIMARY: matches=$matches mgdl=$mgdl rangeOk=${mgdl in 20..600}"
            }
            if (matches == 1 && mgdl in 20..600) {
                val dir = extractDirection(extrasCombined, input.contentViewTexts)
                DebugTrace.v(DebugCategory.PARSING, "PARSER-V-DIR") {
                    "Direction (PRIMARY): extrasCombined=[$extrasCombined] → dir=$dir"
                }
                val sample = GlucoseSample(
                    timestamp, mgdl, dir,
                    input.sourcePackage,
                    extrasCombined.ifBlank { input.contentViewTexts.joinToString(" ") },
                    reStatus.find(extrasCombined)?.value,
                    reAlert.find(extrasCombined)?.value
                )
                DebugTrace.v(DebugCategory.PARSING, "PARSER-V-RESULT") {
                    "PRIMARY result: ts=$timestamp mgdl=$mgdl dir=$dir " +
                    "status=${sample.sensorStatus} alert=${sample.alertText}"
                }
                return sample
            }
        }

        // === FALLBACK PATH — extras ===
        DebugTrace.v(DebugCategory.PARSING, "PARSER-V-PATH") { "Trying FALLBACK path (extras)" }

        // 1) Title after xDrip-style filtering
        val titleMgdl = tryExtractFromTitle(input.title)
        DebugTrace.v(DebugCategory.PARSING, "PARSER-V-FALLBACK") {
            "FALLBACK step1 title=[${input.title.orEmpty()}] titleMgdl=$titleMgdl"
        }
        if (titleMgdl in 20..600) {
            val sample = buildSample(timestamp, titleMgdl, extrasCombined, input.sourcePackage)
            DebugTrace.v(DebugCategory.PARSING, "PARSER-V-RESULT") {
                "FALLBACK(title) result: ts=$timestamp mgdl=$titleMgdl dir=${sample.direction}"
            }
            return sample
        }

        // 2) Explicit mg/dL unit
        reMgdl.find(extrasCombined)?.let {
            val v = it.groupValues[1].toInt()
            DebugTrace.v(DebugCategory.PARSING, "PARSER-V-FALLBACK") {
                "FALLBACK step2 mgdl regex match=[${it.value}] mgdl=$v"
            }
            val sample = buildSample(timestamp, v, extrasCombined, input.sourcePackage)
            DebugTrace.v(DebugCategory.PARSING, "PARSER-V-RESULT") {
                "FALLBACK(mgdl-unit) result: ts=$timestamp mgdl=$v dir=${sample.direction}"
            }
            return sample
        }

        // 3) Explicit mmol/L unit
        reMmol.find(extrasCombined)?.let {
            val v = GlucoseUnitConverter.mmolToMgdl(it.groupValues[1].replace(',', '.').toDouble())
            DebugTrace.v(DebugCategory.PARSING, "PARSER-V-FALLBACK") {
                "FALLBACK step3 mmol regex match=[${it.value}] convertedMgdl=$v"
            }
            val sample = buildSample(timestamp, v, extrasCombined, input.sourcePackage)
            DebugTrace.v(DebugCategory.PARSING, "PARSER-V-RESULT") {
                "FALLBACK(mmol-unit) result: ts=$timestamp mgdl=$v dir=${sample.direction}"
            }
            return sample
        }

        // 4) Status / alert only
        val status = reStatus.find(extrasCombined)?.value
        val alert = reAlert.find(extrasCombined)?.value
        DebugTrace.v(DebugCategory.PARSING, "PARSER-V-FALLBACK") {
            "FALLBACK step4 status=[$status] alert=[$alert]"
        }
        if (status != null || alert != null) {
            DebugTrace.v(DebugCategory.PARSING, "PARSER-V-RESULT") {
                "StatusOnly: status=$status alert=$alert"
            }
            return GlucoseSample(timestamp, -1, "NONE", input.sourcePackage, extrasCombined, status, alert)
        }

        DebugTrace.v(DebugCategory.PARSING, "PARSER-V-RESULT") { "No parse result — returning null" }
        return null
    }

    // --- helpers ---

    private fun buildSample(ts: Long, mgdl: Int, text: String, pkg: String): GlucoseSample {
        val trendRaw = reTrend.find(text)?.value
        val dir = TrendDirectionMapper.map(trendRaw)
        DebugTrace.v(DebugCategory.PARSING, "PARSER-V-DIR") {
            "Direction (FALLBACK): trendRaw=[$trendRaw] → dir=$dir in text=[$text]"
        }
        return GlucoseSample(
            ts, mgdl, dir,
            pkg, text, reStatus.find(text)?.value, reAlert.find(text)?.value
        )
    }

    private fun tryExtractFiltered(filtered: String): Int {
        val t = filtered.trim()
        if (t.isEmpty()) return -1
        if (reBareInt.matches(t)) return t.toIntOrNull() ?: -1
        if (CgmStringFilter.isValidMmol(t)) {
            return try { GlucoseUnitConverter.mmolToMgdl(t.replace(',', '.').toDouble()) }
            catch (_: NumberFormatException) { -1 }
        }
        return -1
    }

    private fun tryExtractFromTitle(title: String?): Int {
        if (title.isNullOrBlank()) return -1
        return tryExtractFiltered(CgmStringFilter.filter(title))
    }

    private fun extractDirection(extras: String, cvTexts: List<String>): String {
        reTrend.find(extras)?.value?.let {
            DebugTrace.v(DebugCategory.PARSING, "PARSER-V-DIR-DETAIL") { "Direction from extras: raw=[$it]" }
            return TrendDirectionMapper.map(it)
        }
        for (t in cvTexts) {
            reTrend.find(t)?.value?.let {
                DebugTrace.v(DebugCategory.PARSING, "PARSER-V-DIR-DETAIL") { "Direction from cvText=[$t]: raw=[$it]" }
                return TrendDirectionMapper.map(it)
            }
        }
        DebugTrace.v(DebugCategory.PARSING, "PARSER-V-DIR-DETAIL") { "No trend token found → NONE" }
        return "NONE"
    }
}
