package tw.yourcompany.cgmbridge.feature.input.notification

import tw.yourcompany.cgmbridge.core.logging.DebugCategory
import tw.yourcompany.cgmbridge.core.logging.DebugTrace
import tw.yourcompany.cgmbridge.core.model.GlucoseSample
import tw.yourcompany.cgmbridge.core.source.SourceIdentity
import tw.yourcompany.cgmbridge.core.source.TransportType

/**
 * Parses one CGM notification into a normalized [GlucoseSample].
 *
 * Parsing strategy:
 * 1. Try the inflated `contentView` text list first because that most closely matches what the
 *    user actually sees on screen.
 * 2. Fall back to standard extras such as `EXTRA_TITLE` and `EXTRA_TEXT`.
 * 3. Normalize the vendor and transport immediately so downstream code never needs to guess
 *    whether `com.microtech.aidexx` and `aidex` are the same logical source.
 *
 * The identity normalization in step 3 is part of the multi-source bug fix. Without it,
 * different package variants could leak into the database as different pseudo-vendors and the
 * graph would fail to group readings correctly.
 */
class GenericCgmNotificationParser {

    private val reMgdl = Regex("(\\b\\d{2,3}\\b)\\s*(mg\\s*/\\s*d[l1]|mg/dl|mgdl)", RegexOption.IGNORE_CASE)
    private val reMmol = Regex("(\\b\\d{1,2}(?:[.,]\\d{1,2})?)\\s*(mmol\\s*/\\s*l|mmol/l|mmoll)", RegexOption.IGNORE_CASE)
    private val reTrend = Regex("(↑↑|↑|↗|→|↘|↓↓|↓|DoubleUp|SingleUp|FortyFiveUp|Flat|FortyFiveDown|SingleDown|DoubleDown)", RegexOption.IGNORE_CASE)
    private val reStatus = Regex("(signal loss|no signal|sensor error|calibration|warmup|expired|replace sensor)", RegexOption.IGNORE_CASE)
    private val reAlert = Regex("(urgent low|urgent high|low|high|alarm|alert)", RegexOption.IGNORE_CASE)
    private val reBareInt = Regex("^-?\\d{2,3}$")

    /**
     * Parses the notification snapshot.
     *
     * The returned [GlucoseSample] already contains a normalized `sourceId`, normalized
     * `vendorName`, and a canonical transport value of `NOTIFICATION`. This makes the importer,
     * database, and graph code deterministic and removes the older package-name leakage bug.
     */
    fun parse(input: NotificationParseInput): GlucoseSample? {
        val extrasTokens = listOfNotNull(input.title, input.text, input.subText, input.bigText, input.textLines, input.tickerText)
        val extrasCombined = extrasTokens.joinToString(" ").trim()

        DebugTrace.v(DebugCategory.PARSING, "PARSER-V-INPUT") {
            "pkg=${input.sourcePackage} cvTexts(${input.contentViewTexts.size})=${input.contentViewTexts} extrasCombined=[$extrasCombined] postTimeMs=${input.postTimeMs}"
        }

        val timestamp = NotificationTimestampExtractor.extractEpochMs(
            extrasCombined.ifBlank { input.title ?: input.text },
            input.postTimeMs
        )

        DebugTrace.v(DebugCategory.PARSING, "PARSER-V-TS") { "resolvedTimestampMs=$timestamp (fallback=${input.postTimeMs})" }

        // PRIMARY PATH — scan every visible TextView extracted from the RemoteViews tree.
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
                if (parsed > 0) {
                    mgdl = parsed
                    matches++
                }
            }
            DebugTrace.v(DebugCategory.PARSING, "PARSER-V-CV-RESULT") {
                "PRIMARY: matches=$matches mgdl=$mgdl rangeOk=${mgdl in 20..600}"
            }
            if (matches == 1 && mgdl in 20..600) {
                val dir = extractDirection(extrasCombined, input.contentViewTexts)
                val sample = buildSample(
                    ts = timestamp,
                    mgdl = mgdl,
                    text = extrasCombined.ifBlank { input.contentViewTexts.joinToString(" ") },
                    pkg = input.sourcePackage,
                    explicitDirection = dir
                )
                DebugTrace.v(DebugCategory.PARSING, "PARSER-V-RESULT") {
                    "PRIMARY result: ts=$timestamp mgdl=$mgdl dir=$dir status=${sample.sensorStatus} alert=${sample.alertText} sourceId=${sample.sourceId}"
                }
                return sample
            }
        }

        // FALLBACK PATH — title / text / bigText / ticker text.
        DebugTrace.v(DebugCategory.PARSING, "PARSER-V-PATH") { "Trying FALLBACK path (extras)" }

        val titleMgdl = tryExtractFromTitle(input.title)
        if (titleMgdl in 20..600) {
            val sample = buildSample(timestamp, titleMgdl, extrasCombined, input.sourcePackage)
            DebugTrace.v(DebugCategory.PARSING, "PARSER-V-RESULT") {
                "FALLBACK title result: ts=$timestamp mgdl=$titleMgdl dir=${sample.direction} sourceId=${sample.sourceId}"
            }
            return sample
        }

        reMgdl.find(extrasCombined)?.let {
            val v = it.groupValues[1].toInt()
            if (v in 20..600) {
                val sample = buildSample(timestamp, v, extrasCombined, input.sourcePackage)
                DebugTrace.v(DebugCategory.PARSING, "PARSER-V-RESULT") {
                    "FALLBACK mgdl result: ts=$timestamp mgdl=$v dir=${sample.direction} sourceId=${sample.sourceId}"
                }
                return sample
            }
        }

        reMmol.find(extrasCombined)?.let {
            val v = GlucoseUnitConverter.mmolToMgdl(it.groupValues[1].replace(',', '.').toDouble())
            if (v in 20..600) {
                val sample = buildSample(timestamp, v, extrasCombined, input.sourcePackage)
                DebugTrace.v(DebugCategory.PARSING, "PARSER-V-RESULT") {
                    "FALLBACK mmol result: ts=$timestamp mgdl=$v dir=${sample.direction} sourceId=${sample.sourceId}"
                }
                return sample
            }
        }

        val status = reStatus.find(extrasCombined)?.value
        val alert = reAlert.find(extrasCombined)?.value
        if (!status.isNullOrBlank() || !alert.isNullOrBlank()) {
            DebugTrace.v(DebugCategory.PARSING, "PARSER-V-STATUS") {
                "Status-only notification: status=$status alert=$alert"
            }
            val vendor = SupportedPackages.vendorForPackage(input.sourcePackage)
            val transport = TransportType.NOTIFICATION
            return GlucoseSample(
                sourceId = SourceIdentity.buildSourceId(transport, vendor, null),
                transportType = transport.name,
                vendorName = vendor,
                originKey = null,
                timestampMs = timestamp,
                valueMgdl = -1,
                direction = "NONE",
                rawText = extrasCombined,
                sensorStatus = status,
                alertText = alert
            )
        }

        DebugTrace.v(DebugCategory.PARSING, "PARSER-V-RESULT") { "No BG parse result for pkg=${input.sourcePackage}" }
        return null
    }

    /**
     * Builds a fully normalized [GlucoseSample].
     *
     * This helper is the key part of the source-identity fix:
     * - vendor name comes from [SupportedPackages.vendorForPackage],
     * - transport is always `NOTIFICATION`,
     * - `sourceId` is built from the normalized identity fields rather than from the raw Android
     *   package name.
     */
    private fun buildSample(ts: Long, mgdl: Int, text: String, pkg: String, explicitDirection: String? = null): GlucoseSample {
        val trendRaw = reTrend.find(text)?.value
        val dir = explicitDirection ?: TrendDirectionMapper.map(trendRaw)
        val vendor = SupportedPackages.vendorForPackage(pkg)
        val transport = TransportType.NOTIFICATION
        DebugTrace.v(DebugCategory.PARSING, "PARSER-V-DIR") {
            "Direction: trendRaw=[$trendRaw] explicit=$explicitDirection -> dir=$dir in text=[$text]"
        }
        return GlucoseSample(
            sourceId = SourceIdentity.buildSourceId(transport, vendor, null),
            transportType = transport.name,
            vendorName = vendor,
            originKey = null,
            timestampMs = ts,
            valueMgdl = mgdl,
            direction = dir,
            rawText = text,
            sensorStatus = reStatus.find(text)?.value,
            alertText = reAlert.find(text)?.value
        )
    }

    /**
     * Attempts to parse a single already-filtered text token into mg/dL.
     *
     * The method accepts:
     * - integer mg/dL values such as `120`
     * - decimal mmol/L values such as `5.6` or `5,6`
     *
     * Any unrecognized token returns `-1`.
     */
    private fun tryExtractFiltered(filtered: String): Int {
        val t = filtered.trim()
        if (t.isEmpty()) return -1
        if (reBareInt.matches(t)) return t.toIntOrNull() ?: -1
        if (CgmStringFilter.isValidMmol(t)) {
            return try {
                GlucoseUnitConverter.mmolToMgdl(t.replace(',', '.').toDouble())
            } catch (_: NumberFormatException) {
                -1
            }
        }
        return -1
    }

    /**
     * Tries to parse the notification title as a glucose token.
     * Some vendors place the number in the title rather than the body.
     */
    private fun tryExtractFromTitle(title: String?): Int {
        if (title.isNullOrBlank()) return -1
        return tryExtractFiltered(CgmStringFilter.filter(title))
    }

    /**
     * Extracts a normalized trend direction from either extras text or contentView text.
     *
     * Extras are checked first because some vendors duplicate the visible arrow there, but the
     * contentView path remains as a backup for layouts where the arrow exists only inside the
     * custom notification UI.
     */
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
        DebugTrace.v(DebugCategory.PARSING, "PARSER-V-DIR-DETAIL") { "No trend token found -> NONE" }
        return "NONE"
    }
}