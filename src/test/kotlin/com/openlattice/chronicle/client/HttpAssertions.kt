package com.openlattice.chronicle.client

import com.geekbeast.retrofit.RhizomeRetrofitCallException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.springframework.http.HttpStatus

internal fun assertHttpStatus(status: HttpStatus, request: () -> Unit): RhizomeRetrofitCallException {
    val failure = assertThrows(RhizomeRetrofitCallException::class.java) { request() }
    assertEquals(status.value(), failure.code)
    return failure
}
