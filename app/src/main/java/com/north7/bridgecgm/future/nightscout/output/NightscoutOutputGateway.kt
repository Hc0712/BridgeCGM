package com.north7.bridgecgm.future.nightscout.output

import com.north7.bridgecgm.core.model.GlucoseSample

/**
 * Placeholder contract for a future Nightscout output pipeline.
 *
 * Current state:
 * - The shipping bridge does not upload to Nightscout yet.
 * - This file documents the architectural extension point so the team can add the feature later
 *   without re-opening the current xDrip output design.
 *
 * Expected behavior when implemented:
 * - Accept a normalized [GlucoseSample].
 * - Perform any Nightscout-specific mapping in one place.
 * - Keep transport / retry details inside the future Nightscout feature package.
 */
interface NightscoutOutputGateway {
    /**
     * Sends one normalized glucose sample to a future Nightscout destination.
     *
     * This method is intentionally left unimplemented in the clean refactor.
     * It exists only to give the project a clear expansion seam.
     */
    suspend fun send(sample: GlucoseSample)
}
