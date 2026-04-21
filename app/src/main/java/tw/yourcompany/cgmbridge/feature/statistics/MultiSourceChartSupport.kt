package tw.yourcompany.cgmbridge.feature.statistics

import tw.yourcompany.cgmbridge.core.db.BgReadingEntity
import tw.yourcompany.cgmbridge.core.db.CgmSourceEntity

/**
 * Pure helper for turning stored rows into graph-ready logical series.
 *
 * Responsibilities:
 * - build one raw series per visible source for the main graph;
 * - add one extra calibrated overlay for the selected primary source when calibration is on;
 * - build exactly one primary-only series for the mini graph.
 *
 * The object deliberately does not know anything about MPAndroidChart. That keeps the business
 * rules testable and makes the chart renderer simpler.
 */
object MultiSourceChartSupport {
    /**
     * One logical graph series before it is turned into a chart-library dataset.
     *
     * @param sourceId            stable source identifier owning the rows
     * @param label               human-readable legend label
     * @param colorArgb           stable display color for the series
     * @param useCalibratedValue  `true` when the chart should read `calibratedValueMgdl`
     *                            instead of `calculatedValueMgdl`
     * @param rows                time-ordered rows that belong to this series
     */
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
     * Rules:
     * - every visible and enabled source receives one raw series;
     * - the selected primary source receives one additional calibrated series only when
     *   calibration is enabled;
     * - the returned rows are sorted oldest -> newest for chart rendering.
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
                label = "${source.vendorName} / ${source.transportType.lowercase()} / Raw",
                colorArgb = source.colorArgb,
                useCalibratedValue = false,
                rows = ordered
            )
            if (calibrationEnabled && primaryOutputSourceId == sourceId) {
                result += GraphSeries(
                    sourceId = sourceId,
                    label = "${source.vendorName} / ${source.transportType.lowercase()} / Calibrated",
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
     *
     * If there is no selected source or no rows for that source, the function returns `null` and
     * the UI should clear the mini graph instead of drawing a mixed-source line.
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