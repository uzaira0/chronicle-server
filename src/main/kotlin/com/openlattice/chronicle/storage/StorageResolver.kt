package com.openlattice.chronicle.storage

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.openlattice.chronicle.configuration.ChronicleStorageConfiguration
import com.geekbeast.jdbc.DataSourceManager
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.hazelcast.processors.storage.StudyStorageRead
import com.openlattice.chronicle.hazelcast.processors.storage.StudyStorageUpdate
import com.openlattice.chronicle.storage.rls.RLSDataSources
import com.openlattice.chronicle.study.Study
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public open class StorageResolver constructor(
    private val dataSourceManager: DataSourceManager,
    private val storageConfiguration: ChronicleStorageConfiguration
) {
    @Volatile
    private var studyStorage: IMap<UUID, Study>? = null
    private val validatedEventStorages = ConcurrentHashMap.newKeySet<String>()
    private val loggedPlatformReadFallback = AtomicBoolean(false)

    private companion object {
        private val logger = LoggerFactory.getLogger(StorageResolver::class.java)
    }

    public fun associateStudyWithStorage(studyId: UUID, storage: String = ChronicleStorage.CHRONICLE.id) {
        requireDeletionStorageColocated(storage, getDataSource(storage).second)
        checkNotNull(studyStorage) { "Study storage map has not been initialized" }
            .executeOnKey(studyId, StudyStorageUpdate(storage))
    }

    public fun resolve(studyId: UUID, requiredFlavor: PostgresFlavor = PostgresFlavor.VANILLA): HikariDataSource {
        val (flavor, hds) = resolveAndGetFlavor(studyId)
        check(flavor == PostgresFlavor.ANY || flavor == requiredFlavor) { "Configured flavor $flavor does not much required flavor $requiredFlavor" }
        return hds
    }

    public fun resolveAndGetFlavor(studyId: UUID): Pair<PostgresFlavor, HikariDataSource> {
        val dataSourceName = resolveDataSourceName(studyId)
        val resolved = getDataSource(dataSourceName)
        if (validatedEventStorages.add(dataSourceName)) {
            try {
                requireDeletionStorageColocated(resolved.second)
            } catch (exception: Exception) {
                validatedEventStorages.remove(dataSourceName)
                throw exception
            }
        }
        return resolved
    }

    public fun resolveDataSourceName(studyId: UUID): String {
        return studyStorage?.executeOnKey(studyId, StudyStorageRead()) ?: storageConfiguration.defaultEventStorage
    }

    public fun getStudyIdsByDataSourceName(studyIds: Collection<UUID>): Map<String, List<UUID>> {
        return studyIds.groupBy { resolveDataSourceName(it) }
    }

    public fun getDataSource(dataSourceName: String): Pair<PostgresFlavor, HikariDataSource> {
        return dataSourceManager.getFlavor(dataSourceName) to rlsAware(dataSourceManager.getDataSource(dataSourceName))
    }

    public fun getAuditStorage(): Pair<PostgresFlavor, HikariDataSource> {
        return with(dataSourceManager) {
            getFlavor(storageConfiguration.auditStorage) to rlsAware(getDataSource(storageConfiguration.auditStorage))
        }
    }

    public fun getEventStorageWithFlavor(requiredFlavor: PostgresFlavor = PostgresFlavor.VANILLA): HikariDataSource {
        val (flavor, hds) = getDefaultEventStorage()
        check(flavor == PostgresFlavor.ANY || flavor == requiredFlavor) { "Configured flavor $flavor does not match required flavor $requiredFlavor" }
        return hds
    }

    public open fun getPlatformStorage(requiredFlavor: PostgresFlavor = PostgresFlavor.VANILLA): HikariDataSource {
        val (flavor, hds) = getDefaultPlatformStorage()
        check(flavor == PostgresFlavor.ANY || flavor == requiredFlavor) { "Configured flavor $flavor does not match required flavor $requiredFlavor" }
        return hds
    }

    public fun getPlatformReadStorage(requiredFlavor: PostgresFlavor = PostgresFlavor.VANILLA) : HikariDataSource {
        val (flavor, hds) =  getDefaultPlatformReadStorage()
        check(flavor == PostgresFlavor.ANY || flavor == requiredFlavor) { "Configured flavor $flavor does not match required flavor $requiredFlavor" }
        return hds
    }

    /**
     * Resolves the read-side platform datasource.
     *
     * [ChronicleStorageConfiguration.platformReadStorage] defaults to `platform_read`, which is a
     * *read-replica* endpoint: it exists so deep export/download queueing does not compete with the
     * website on the primary. Single-database deployments — the self-host bundle, the Docker
     * compose stack and the k8s base config — have no replica and therefore register no
     * `platform_read` datasource at all, so every read through this method used to die with
     * `NoSuchElementException: Key platform_read is missing in the map`, taking down full-study
     * export and every Time Use Diary read with it.
     *
     * When the configured read datasource is not registered we fall back to the platform (write)
     * datasource and say so in the log. The fallback is *not* an RLS weakening: the returned
     * datasource goes through the same [rlsAware] wrapper, so a request- or export-worker-scoped
     * connection still drops to the non-superuser `chronicle_app` role via `SET ROLE` and still
     * carries `app.current_user_id` / `app.authorized_studies` / `app.is_admin`. It is literally
     * the same pool the write path already uses under the same role, so study isolation is
     * identical — the only thing given up is replica offloading, which does not exist here.
     */
    public fun getDefaultPlatformReadStorage(): Pair<PostgresFlavor, HikariDataSource> {
        val configured = storageConfiguration.platformReadStorage
        val resolved = if (isDataSourceRegistered(configured)) {
            configured
        } else {
            val fallback = storageConfiguration.platformStorage
            check(isDataSourceRegistered(fallback)) {
                "Neither the configured platform read storage '$configured' nor the platform " +
                    "storage fallback '$fallback' is a registered datasource"
            }
            if (loggedPlatformReadFallback.compareAndSet(false, true)) {
                logger.warn(
                    "No '{}' datasource is configured; platform reads (export, time use diary) will " +
                        "use the '{}' datasource instead. RLS is unchanged — the fallback pool is " +
                        "wrapped by the same request-scoped SET ROLE. Declare a '{}' datasource to " +
                        "route these reads at a read replica.",
                    configured,
                    fallback,
                    configured,
                )
            }
            fallback
        }
        return with(dataSourceManager) {
            getFlavor(resolved) to rlsAware(getDataSource(resolved))
        }
    }

    private fun isDataSourceRegistered(name: String): Boolean = dataSourceManager.dataSources.containsKey(name)

    public fun getDefaultPlatformStorage(): Pair<PostgresFlavor, HikariDataSource> {
        return with(dataSourceManager) {
            getFlavor(storageConfiguration.platformStorage) to rlsAware(getDataSource(storageConfiguration.platformStorage))
        }
    }

    public fun getDefaultEventStorage(): Pair<PostgresFlavor, HikariDataSource> {
        return with(dataSourceManager) {
            getFlavor(storageConfiguration.defaultEventStorage) to rlsAware(getDataSource(storageConfiguration.defaultEventStorage))
        }
    }

    /**
     * Verified deletion currently uses one transactionally consistent PostgreSQL
     * database/schema for both platform and event tables. Refuse unsupported
     * split-storage configurations instead of proving erasure against the wrong
     * datastore.
     */
    public fun requireDefaultDeletionStorageColocated() {
        requireDeletionStorageColocated(getDefaultEventStorage().second)
    }

    /**
     * Re-check per-study routing at quarantine and worker execution boundaries.
     * This also catches a stale/custom Hazelcast storage association that
     * predates the guarded association writer below.
     */
    public fun requireDeletionStorageColocated(studyId: UUID) {
        val dataSourceName = resolveDataSourceName(studyId)
        requireDeletionStorageColocated(dataSourceName, getDataSource(dataSourceName).second)
    }

    public fun setStudyStorage( hazelcastInstance: HazelcastInstance ) {
        studyStorage = HazelcastMap.STUDIES.getMap(hazelcastInstance)
    }

    private fun requireDeletionStorageColocated(
        dataSourceName: String,
        eventStorage: HikariDataSource,
    ) {
        requireDeletionStorageColocated(eventStorage)
        validatedEventStorages.add(dataSourceName)
    }

    private fun requireDeletionStorageColocated(eventStorage: HikariDataSource) {
        getPlatformStorage().connection.use { platformConnection ->
            eventStorage.connection.use { eventConnection ->
                requireSamePostgresLockDomain(platformConnection, eventConnection, UUID.randomUUID().mostSignificantBits)
            }
        }
    }

    internal fun requireSamePostgresLockDomain(
        platformConnection: Connection,
        eventConnection: Connection,
        probeKey: Long,
    ) {
        val platformAutoCommit = platformConnection.autoCommit
        val eventAutoCommit = eventConnection.autoCommit
        try {
            platformConnection.autoCommit = false
            eventConnection.autoCommit = false
            val platformIdentity = readStorageIdentity(platformConnection)
            val eventIdentity = readStorageIdentity(eventConnection)
            check(platformIdentity == eventIdentity) {
                "Verified deletion requires platform and event storage to use the same PostgreSQL database and schema search path"
            }

            platformConnection.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
                statement.setLong(1, probeKey)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "PostgreSQL did not acquire the deletion topology probe lock" }
                }
            }
            val secondConnectionAcquired = eventConnection.prepareStatement(
                "SELECT pg_try_advisory_xact_lock(?)",
            ).use { statement ->
                statement.setLong(1, probeKey)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "PostgreSQL did not return a deletion topology probe result" }
                    resultSet.getBoolean(1)
                }
            }
            check(!secondConnectionAcquired) {
                "Verified deletion requires platform and event storage to share one PostgreSQL advisory-lock domain"
            }
        } finally {
            try {
                eventConnection.rollback()
            } finally {
                platformConnection.rollback()
                eventConnection.autoCommit = eventAutoCommit
                platformConnection.autoCommit = platformAutoCommit
            }
        }
    }

    private fun readStorageIdentity(connection: Connection): PostgresStorageIdentity =
        connection.prepareStatement(
            """
            SELECT current_database() AS database_name,
                   current_schemas(false) AS schema_names
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "PostgreSQL did not return a storage identity" }
                PostgresStorageIdentity(
                    database = resultSet.getString("database_name"),
                    schemas = (resultSet.getArray("schema_names").array as Array<*>)
                        .map { it.toString() },
                )
            }
        }

    private fun rlsAware(hds: HikariDataSource): HikariDataSource = RLSDataSources.wrapIfRequestScoped(hds)

    private data class PostgresStorageIdentity(
        val database: String,
        val schemas: List<String>,
    )
}
