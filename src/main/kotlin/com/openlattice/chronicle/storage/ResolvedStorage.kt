package com.openlattice.chronicle.storage

import com.geekbeast.configuration.postgres.PostgresFlavor
import com.zaxxer.hikari.HikariDataSource

/** A routed storage binding; the pool is owned by DataSourceManager. */
public class ResolvedStorage(public val flavor: PostgresFlavor, public val dataSource: HikariDataSource)
