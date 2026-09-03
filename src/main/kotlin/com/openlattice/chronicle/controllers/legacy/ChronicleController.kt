package com.openlattice.chronicle.controllers.legacy

import com.codahale.metrics.annotation.Timed
import com.google.common.collect.SetMultimap
import com.openlattice.chronicle.ChronicleApi
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.services.enrollment.EnrollmentManager
import com.openlattice.chronicle.services.legacy.LegacyEdmResolver
import com.openlattice.chronicle.services.studies.StudyManager
import com.openlattice.chronicle.services.upload.AppDataUploadManager
import com.openlattice.chronicle.util.DeviceIdUtils
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import java.util.*
import jakarta.inject.Inject

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@RestController
@RequestMapping(ChronicleApi.CONTROLLER)
@Validated
@RateLimit(type = RateLimitType.DEFAULT)
public open class ChronicleController : ChronicleApi {
    @Inject
    private lateinit var dataUploadManager: AppDataUploadManager

    // reason: @Inject field-injected DI dependency retained for the controller's bean wiring
    @Suppress("UnusedPrivateProperty")
    @Inject
    private lateinit var enrollmentManager: EnrollmentManager

    @Inject
    private lateinit var studyManager: StudyManager

    @Timed
    @RequestMapping(
            path = [ChronicleApi.STUDY_ID_PATH + ChronicleApi.PARTICIPANT_ID_PATH + ChronicleApi.DATASOURCE_ID_PATH],
            method = [RequestMethod.POST], consumes = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun upload(
            @PathVariable(ChronicleApi.STUDY_ID) studyId: UUID,
            @PathVariable(ChronicleApi.PARTICIPANT_ID) participantId: String,
            @PathVariable(ChronicleApi.DATASOURCE_ID) datasourceId: String,
            @Valid @RequestBody @Size(max = 10_000) data: List<SetMultimap<UUID, Any>>
    ): Int {
        val realStudyId = studyManager.getStudyId(studyId)
        checkNotNull(realStudyId) { "invalid study id" }
        val deviceId = DeviceIdUtils.deriveDeviceId(realStudyId, participantId, datasourceId)
        return dataUploadManager.upload(realStudyId, participantId, deviceId, data)
    }

    @Timed
    @RequestMapping(
            path = [ChronicleApi.EDM_PATH], method = [RequestMethod.POST],
            consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun getPropertyTypeIds(@Valid @RequestBody propertyTypeFqns: Set<String>): Map<String, UUID> {
        return LegacyEdmResolver.getLegacyPropertyTypeIds(propertyTypeFqns)
    }

    @Timed
    @RequestMapping(path = [ChronicleApi.STATUS_PATH], method = [RequestMethod.GET])
    override fun isRunning(): Boolean {
        return true
    }
}
