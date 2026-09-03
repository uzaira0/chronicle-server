package com.openlattice.chronicle.storage

import java.net.URL
import java.util.*


public interface ByteBlobDataManager {

    public companion object {
        @JvmStatic
        public fun generateBlobKey(
                entitySetId: UUID,
                entityKeyId: UUID,
                propertyTypeId: UUID,
                digest: String
        ): String {
            return "$entitySetId/$entityKeyId/$propertyTypeId/$digest"
        }
    }

    public fun putObject(blobKey: String, binaryObjectWithMetadata: BinaryObjectWithMetadata)

    public fun deleteObject(blobKey: String)

    public fun getObjects(keys: Collection<Any>): List<Any>

    public fun getPresignedUrl(
            key: Any,
            expiration: Date,
            contentType: String? = null,
            contentDisposition: String? = null
    ): URL

    public fun getPresignedUrls(keys: Collection<Any>): List<URL>

    public fun getPresignedUrlsWithDispositions(keysToDispositions: Map<String, String?>): Map<String, URL>

    public fun deleteObjects(blobKeys: List<String>)

    public fun getDefaultExpirationDateTime(): Date
}
