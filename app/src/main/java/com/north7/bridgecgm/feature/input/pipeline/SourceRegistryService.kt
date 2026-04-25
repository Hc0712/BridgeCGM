package com.north7.bridgecgm.feature.input.pipeline

import com.north7.bridgecgm.core.data.Repository
import com.north7.bridgecgm.core.source.SourceIdentity
import com.north7.bridgecgm.core.source.TransportType

/**
 * Keeps the source registry fresh whenever a normalized reading arrives.
 *
 * The service is deliberately tiny: production source registration should be automatic
 * and low-risk. More advanced source editing belongs in future UI screens.
 */
class SourceRegistryService(private val repo: Repository) {
    suspend fun ensureRegistered(reading: NormalizedReading) {
        val now = System.currentTimeMillis()
        val transport = try {
            TransportType.valueOf(reading.transportType.uppercase())
        } catch (_: Exception) {
            TransportType.NOTIFICATION
        }
        val entity = SourceIdentity.newEntity(transport, reading.vendorName, reading.originKey, now)
            .copy(sourceId = reading.sourceId, lastSeenAtMs = reading.timestampMs, updatedAtMs = now)
        repo.upsertSource(entity)
    }
}
