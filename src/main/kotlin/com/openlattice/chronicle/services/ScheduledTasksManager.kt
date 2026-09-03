package com.openlattice.chronicle.services

import com.geekbeast.postgres.streams.BasePostgresIterable
import com.geekbeast.postgres.streams.PreparedStatementHolderSupplier
import com.openlattice.chronicle.postgres.ResultSetAdapters
import com.openlattice.chronicle.storage.ChroniclePostgresTables.Companion.SYSTEM_APPS
import com.openlattice.chronicle.storage.PostgresEventColumns.Companion.APP_PACKAGE_NAME
import com.openlattice.chronicle.storage.StorageResolver
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

private const val SYSTEM_APPS_REFRESH_INTERVAL = (60 * 60 * 1000L) // 1 hour

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public open class ScheduledTasksManager(
        private val storageResolver: StorageResolver
) {

    internal companion object {
        private val logger = LoggerFactory.getLogger(ScheduledTasksManager::class.java)
    }

    @Volatile
    private var _systemAppPackageNames: Set<String> = emptySet()

    public val systemAppPackageNames: Set<String> get() = _systemAppPackageNames

    // reason: scheduled-task boundary — a cache-refresh failure must be logged, not propagated
    // out of the scheduler thread where it could halt future runs
    @Suppress("TooGenericExceptionCaught")
    @Scheduled(fixedRate = SYSTEM_APPS_REFRESH_INTERVAL)
    public fun refreshSystemApps() {
        try {
            val apps = BasePostgresIterable(
                PreparedStatementHolderSupplier(
                    hds = storageResolver.getPlatformStorage(),
                    sql = "SELECT ${APP_PACKAGE_NAME.name} from ${SYSTEM_APPS.name}"
                ){}
            ){
                ResultSetAdapters.systemApp(it)
            }.toSet()
            // Atomic swap — readers never see a partially-updated set
            _systemAppPackageNames = apps
            logger.info("loaded ${apps.size} system apps into cache")
        } catch (ex: Exception) {
            logger.error("Failed to refresh system apps cache", ex)
        }
    }
}
