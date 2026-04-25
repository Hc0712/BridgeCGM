package com.north7.bridgecgm.future.nightscout.input

/**
 * Placeholder contract for a future Nightscout input pipeline.
 *
 * Why this file exists:
 * - The clean refactor deliberately reserves a stable location for a second input source.
 * - The current production bridge only supports notification-based CGM input.
 * - Future developers can implement Nightscout pull / sync logic here without mixing unfinished
 *   work into the active notification pipeline.
 *
 * Safety rule for future implementation:
 * - Any Nightscout ingestion must normalize incoming glucose data into the same internal mg/dL
 *   model used by the rest of the app before it is stored or broadcast.
 * - This avoids duplicated conversion rules scattered across multiple feature packages.
 */
interface NightscoutInputGateway
