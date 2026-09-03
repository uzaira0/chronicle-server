package com.openlattice.chronicle.serializers.decorators

import com.openlattice.chronicle.storage.ByteBlobDataManager


public interface ByteBlobDataManagerAware {
    public fun setByteBlobDataManager(byteBlobDataManager: ByteBlobDataManager)
}
