package com.openlattice.chronicle.storage.local

import com.openlattice.chronicle.storage.BinaryObjectWithMetadata
import com.openlattice.chronicle.storage.ByteBlobDataManager
import com.zaxxer.hikari.HikariDataSource
import org.springframework.stereotype.Service
import java.net.URL
import java.util.*

@Service
public open class LocalBlobDataService(private val hds: HikariDataSource) : ByteBlobDataManager {
    override fun getPresignedUrl(
            key: Any, expiration: Date, contentType: String?, contentDisposition: String?
    ): URL {
        throw UnsupportedOperationException()
    }

    override fun getPresignedUrls(keys: Collection<Any>): List<URL> {
        throw UnsupportedOperationException()
    }

    override fun getPresignedUrlsWithDispositions(keysToDispositions: Map<String, String?>): Map<String, URL> {
        throw UnsupportedOperationException()
    }

    override fun getDefaultExpirationDateTime(): Date {
        throw UnsupportedOperationException()
    }

    override fun putObject(blobKey: String, binaryObjectWithMetadata: BinaryObjectWithMetadata) {
        insertEntity(blobKey, binaryObjectWithMetadata.data)
    }

    override fun deleteObject(blobKey: String) {
        deleteEntity(blobKey)
    }

    override fun deleteObjects(blobKeys: List<String>) {
        blobKeys.forEach { deleteEntity(it) }
    }

    override fun getObjects(keys: Collection<Any>): List<Any> {
        return getEntities(keys)
    }

    public fun insertEntity(blobKey: String, value: ByteArray) {
        hds.connection.use { connection ->
            connection.prepareStatement(insertEntitySql()).use { preparedStatement ->
                preparedStatement.setString(1, blobKey)
                preparedStatement.setBytes(2, value)
                preparedStatement.execute()
            }
        }
    }

    public fun deleteEntity(blobKey: String) {
        hds.connection.use { connection ->
            connection.prepareStatement(DELETE_ENTITY_SQL).use { ps ->
                ps.setString(1, blobKey)
                ps.executeUpdate()
            }
        }
    }

    // reason: connection/prepared-statement/result-set use{} nesting plus the per-key loop is
    // inherent to this multi-key JDBC fetch; extracting would not reduce the resource nesting
    @Suppress("NestedBlockDepth")
    public fun getEntities(keys: Collection<Any>): List<ByteArray> {
        val entities = mutableListOf<ByteArray>()
        hds.connection.use { connection ->
            for (key in keys) {
                connection.prepareStatement(SELECT_ENTITY_SQL).use { ps ->
                    ps.setString(1, key as String)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            entities.add(rs.getBytes(1))
                        }
                    }
                }
            }
        }
        return entities
    }

    internal companion object {
        private const val INSERT_ENTITY_SQL = "INSERT INTO local_blob_store(key, object) VALUES(?, ?)"
        private const val DELETE_ENTITY_SQL = "DELETE FROM local_blob_store WHERE key = ?"
        private const val SELECT_ENTITY_SQL = "SELECT \"object\" FROM local_blob_store WHERE key = ?"
    }

    public fun insertEntitySql(): String = INSERT_ENTITY_SQL
}
