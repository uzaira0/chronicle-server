package com.openlattice.chronicle.e2e.dsl.scopes

import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.e2e.dsl.ChronicleTestDsl
import com.openlattice.chronicle.e2e.dsl.ScenarioContext
import com.openlattice.chronicle.export.ExportJobInfo
import com.openlattice.chronicle.export.ExportJobStatus
import java.util.UUID

@ChronicleTestDsl
class ExportScope(
    val ctx: ScenarioContext,
    val client: ChronicleClient,
    val studyId: UUID,
    val exportId: UUID,
) {
    private var lastInfo: ExportJobInfo? = null

    fun awaitCompletion(timeoutMs: Long = 30_000, intervalMs: Long = 200): ExportJobInfo {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val info = client.testExportApi.getExportStatus(studyId, exportId)
            if (info.status == ExportJobStatus.COMPLETED || info.status == ExportJobStatus.FAILED) {
                lastInfo = info
                return info
            }
            Thread.sleep(intervalMs)
        }
        val last = client.testExportApi.getExportStatus(studyId, exportId)
        lastInfo = last
        error("Export $exportId did not complete within ${timeoutMs}ms; last status: ${last.status}")
    }

    fun download(): ByteArray {
        val info = lastInfo ?: client.testExportApi.getExportStatus(studyId, exportId).also { lastInfo = it }
        check(info.status == ExportJobStatus.COMPLETED) { "Export $exportId is not complete; status=${info.status}" }
        return client.testExportApi.downloadExport(studyId, exportId).bytes()
    }
}
