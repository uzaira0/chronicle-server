package com.openlattice.chronicle.controllers

import com.codahale.metrics.annotation.Timed
import com.openlattice.chronicle.apikey.ApiKeyApi
import com.openlattice.chronicle.apikey.ApiKeyCreateRequest
import com.openlattice.chronicle.apikey.ApiKeyCreateResponse
import com.openlattice.chronicle.apikey.ApiKeyInfo
import com.openlattice.chronicle.apikey.ApiKeyScope
import com.openlattice.chronicle.authorization.ApiKeyStudyAuthority
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.annotations.RequiresStudyAccess
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.configuration.RateLimit
import com.openlattice.chronicle.configuration.RateLimitType
import com.openlattice.chronicle.i18n.Messages
import com.openlattice.chronicle.services.apikeys.ApiKeyService
import com.openlattice.chronicle.study.StudyApi
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.*

@RestController
@RequestMapping(path = [StudyApi.BASE, StudyApi.CONTROLLER])
@Timed
@RateLimit(type = RateLimitType.SENSITIVE)
public open class ApiKeyController(
    private val apiKeyService: ApiKeyService,
    private val authorizationManager: AuthorizationManager,
    private val creatorPrincipalResolver: com.openlattice.chronicle.authorization.CreatorPrincipalResolver,
) : ApiKeyApi {

    /** A key never outranks its creator: MODIFY_STUDY lets a WRITE collaborator here, not mint an ADMIN key. */
    private fun requireScopeWithinOwnAccess(studyId: UUID, scope: ApiKeyScope) {
        val ceiling = ApiKeyStudyAuthority.ceiling(authorizationManager, studyId, creatorPrincipalResolver.resolve(Principals.getCurrentUser().id))
        if (ceiling == null || scope > ceiling) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, Messages.format("error.apiKey.scopeAboveCreator", scope.name))
        }
    }

    @RequiresStudyAccess(StudyPermission.MODIFY_STUDY)
    @PostMapping(
        path = [StudyApi.STUDY_ID_PATH + ApiKeyApi.API_KEYS_PATH],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun createApiKey(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @Valid @RequestBody request: ApiKeyCreateRequest
    ): ApiKeyCreateResponse {
        requireScopeWithinOwnAccess(studyId, request.scope)
        val userId = Principals.getCurrentUser().id
        return apiKeyService.createApiKey(studyId, userId, request)
    }

    @RequiresStudyAccess(StudyPermission.READ_STUDY)
    @GetMapping(
        path = [StudyApi.STUDY_ID_PATH + ApiKeyApi.API_KEYS_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun listApiKeys(@PathVariable(StudyApi.STUDY_ID) studyId: UUID): List<ApiKeyInfo> {
        return apiKeyService.listApiKeys(studyId)
    }

    @RequiresStudyAccess(StudyPermission.MODIFY_STUDY)
    @DeleteMapping(
        path = [StudyApi.STUDY_ID_PATH + ApiKeyApi.API_KEYS_PATH + ApiKeyApi.KEY_ID_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun revokeApiKey(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @PathVariable(ApiKeyApi.KEY_ID) keyId: UUID
    ): OK {
        val userId = Principals.getCurrentUser().id
        apiKeyService.revokeApiKey(studyId, keyId, userId)
        return OK.ok
    }

    @RequiresStudyAccess(StudyPermission.MODIFY_STUDY)
    @PostMapping(
        path = [StudyApi.STUDY_ID_PATH + ApiKeyApi.API_KEYS_PATH + ApiKeyApi.KEY_ID_PATH + ApiKeyApi.ROTATE_PATH],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    override fun rotateApiKey(
        @PathVariable(StudyApi.STUDY_ID) studyId: UUID,
        @PathVariable(ApiKeyApi.KEY_ID) keyId: UUID
    ): ApiKeyCreateResponse {
        // The new key is the rotator's and keeps the old key's scope, so the rotator must be able to hold it.
        apiKeyService.listApiKeys(studyId).find { it.keyId == keyId }
            ?.let { requireScopeWithinOwnAccess(studyId, it.scope) }
        val userId = Principals.getCurrentUser().id
        return apiKeyService.rotateApiKey(studyId, keyId, userId)
    }
}
