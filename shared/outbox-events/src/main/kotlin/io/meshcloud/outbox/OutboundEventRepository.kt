package io.meshcloud.outbox

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface OutboundEventRepository : JpaRepository<OutboundEvent, Long> {

    /**
     * Fetch a batch of unsent events ordered by [OutboundEvent.lastModifiedOn] (FIFO).
     * `FOR UPDATE SKIP LOCKED` allows multiple concurrent scheduler instances to claim
     * disjoint batches without blocking each other.
     *
     * READ_COMMITTED isolation (set on the transaction) prevents gap locks that would
     * block concurrent INSERTs into the same table.
     */
    @Query(
        value = """
            SELECT * FROM outbound_events
            WHERE sent = false AND failed_retries < 100
            ORDER BY last_modified_on ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun findPendingBatch(@Param("batchSize") batchSize: Int): List<OutboundEvent>

    /**
     * Delete sent events older than [before] in small chunks to avoid table-lock escalation.
     *
     * Uses a subquery to apply the LIMIT, because PostgreSQL does not support
     * LIMIT directly in a DELETE statement (unlike MySQL).
     */
    @Modifying
    @Query(
        value = """
            DELETE FROM outbound_events
            WHERE id IN (
                SELECT id FROM outbound_events
                WHERE sent = true AND last_modified_on < :before
                LIMIT :chunkSize
            )
        """,
        nativeQuery = true
    )
    fun deleteOldSentEvents(@Param("before") before: Instant, @Param("chunkSize") chunkSize: Int): Int
}
