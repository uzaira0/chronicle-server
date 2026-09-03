package com.openlattice.chronicle.controllers

import com.openlattice.chronicle.export.ExportJobInfo
import com.openlattice.chronicle.services.export.ExportService
import jakarta.servlet.http.HttpServletResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

class ExportControllerTest {

    private val exportService = Mockito.mock(ExportService::class.java)
    private val controller = ExportController(exportService)

    @Test
    fun testListExportsReturnsServiceResult() {
        val studyId = UUID.randomUUID()
        val expected = emptyList<ExportJobInfo>()
        Mockito.`when`(exportService.listExports(studyId)).thenReturn(expected)

        val result = controller.listExports(studyId)

        assertNotNull(result)
        assertEquals(0, result.size)
    }

    @Test
    fun testGetExportStatusDelegatesToService() {
        val studyId = UUID.randomUUID()
        val exportId = UUID.randomUUID()
        controller.getExportStatus(studyId, exportId)
        verify(exportService).getExportStatus(studyId, exportId)
    }

    @Test
    fun testListExportsDelegatesToService() {
        val studyId = UUID.randomUUID()
        controller.listExports(studyId)
        // Controller passes safeLimit/safeOffset; match any so we don't couple to PaginationDefaults.
        verify(exportService).listExports(kEq(studyId), kAnyInt(), kAnyInt())
    }

    @Test
    fun testDownloadDoesNotAcceptBearerDownloadToken() {
        val method = ExportController::class.java.getDeclaredMethod(
            "downloadExportFile",
            UUID::class.java,
            UUID::class.java,
            HttpServletResponse::class.java
        )

        assertEquals(3, method.parameterCount)
        method.parameterAnnotations.flatten().forEach {
            assertFalse("Export downloads must not accept bearer tokens in query parameters", it is RequestParam && it.value == "token")
        }
    }
}
