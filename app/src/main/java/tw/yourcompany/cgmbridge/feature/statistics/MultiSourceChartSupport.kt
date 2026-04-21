package tw.yourcompany.cgmbridge.feature.statistics

import tw.yourcompany.cgmbridge.core.db.BgReadingEntity
import tw.yourcompany.cgmbridge.core.db.CgmSourceEntity

/**
 * Helper for building graph series in the clean multi-source-first project.
 *
 * Main graph:
 * - all visible raw sources
 * - plus calibrated overlay for the primary source when calibration is enabled
 *
 * Mini graph:
 * - only the primary source
 */
object MultiSourceChartSupport {
    data class GraphSeries(
        val sourceId: String,
        val label: String,
        val colorArgb: Int,
        val useCalibratedValue: Boolean,
        val rows: List<BgReadingEntity>
    )

    /**
     * Builds the main-graph series list.
     *
     * Behavior:
     * - every visible source gets one raw series
     * - the primary source gets an additional calibrated series only when calibration is on
     */
    fun buildMainGraphSeries(
        rows: List<BgReadingEntity>,
        sources: List<CgmSourceEntity>,
        primaryOutputSourceId: String?,
        calibrationEnabled: Boolean
    ): List<GraphSeries> {
        val sourceMap = sources.associateBy { it.sourceId }
        val bySource = rows.groupBy { it.sourceId }
        val result = mutableListOf<GraphSeries>()
        for ((sourceId, sourceRows) in bySource) {
            val source = sourceMap[sourceId] ?: continue
            if (!source.visibleOnMainGraph || !source.enabled) continue
            val ordered = sourceRows.sortedBy { it.timestampMs }
            result += GraphSeries(
                sourceId = sourceId,
                label = "${source.vendorName} / ${source.transportType} / Raw",
                colorArgb = source.colorArgb,
                useCalibratedValue = false,
                rows = ordered
            )
            if (calibrationEnabled && primaryOutputSourceId == sourceId) {
                result += GraphSeries(
                    sourceId = sourceId,
                    label = "${source.vendorName} / ${source.transportType} / Calibrated",
                    colorArgb = source.colorArgb,
                    useCalibratedValue = true,
                    rows = ordered
                )
            }
        }
        return result
    }

    /**
     * Builds the single mini-graph series according to the production rule.
     */
    fun buildMiniGraphSeries(
        rows: List<BgReadingEntity>,
        source: CgmSourceEntity?,
        calibrationEnabled: Boolean
    ): GraphSeries? {
        if (source == null || rows.isEmpty()) return null
        return GraphSeries(
            sourceId = source.sourceId,
            label = source.displayName,
            colorArgb = source.colorArgb,
            useCalibratedValue = calibrationEnabled,
            rows = rows.sortedBy { it.timestampMs }
        )
    }
}
