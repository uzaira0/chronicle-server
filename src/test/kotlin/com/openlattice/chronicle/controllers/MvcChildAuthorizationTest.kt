package com.openlattice.chronicle.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekbeast.controllers.exceptions.ForbiddenException
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.aspects.StudyAuthorizationAspect
import com.openlattice.chronicle.pods.servlet.ChronicleServerMvcPod
import com.openlattice.chronicle.services.export.ExportService
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.springframework.aop.support.AopUtils
import org.springframework.context.support.GenericApplicationContext
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.http.ResponseEntity
import java.util.UUID

class MvcChildAuthorizationTest {
    @RestControllerAdvice
    class TestAdvice {
        @ExceptionHandler(ForbiddenException::class)
        fun forbidden(): ResponseEntity<Void> = ResponseEntity.status(403).build()
    }

    @Test
    fun mvcOwnedControllerUsesParentAuthorizationAspectBeforeDownload() {
        val exports = mock(ExportService::class.java)
        val access = mock(StudyAuthorizationService::class.java)
        val parent = GenericApplicationContext()
        parent.beanFactory.registerSingleton("exports", exports)
        parent.beanFactory.registerSingleton("audit", mock(AuditService::class.java))
        parent.beanFactory.registerSingleton("mapper", ObjectMapper())
        parent.beanFactory.registerSingleton("studyAspect", StudyAuthorizationAspect(access))
        parent.refresh()
        val child = AnnotationConfigWebApplicationContext()
        child.parent = parent
        child.servletContext = MockServletContext()
        child.register(ChronicleServerMvcPod::class.java, TestAdvice::class.java)
        child.addBeanFactoryPostProcessor { factory ->
            val registry = factory as org.springframework.beans.factory.support.BeanDefinitionRegistry
            registry.beanDefinitionNames.filter { name ->
                val type = registry.getBeanDefinition(name).beanClassName.orEmpty()
                type.startsWith("com.openlattice.chronicle.controllers.") && type != ExportController::class.java.name && type != TestAdvice::class.java.name
            }.forEach(registry::removeBeanDefinition)
        }
        try {
            child.refresh()
            val study = UUID.randomUUID()
            val export = UUID.randomUUID()
            TestSecurityUtils.setupSecurityContext(subject = "mvc-export-user", admin = false)
            `when`(exports.getExportStatusForDownload(study, export, "mvc-export-user")).thenReturn(
                com.openlattice.chronicle.export.ExportJobInfo(export, study,
                    com.openlattice.chronicle.export.ExportJobStatus.COMPLETED,
                    com.openlattice.chronicle.export.ExportFormat.CSV, java.time.OffsetDateTime.now()))
            doThrow(ForbiddenException("Denied")).`when`(access).requirePermission(study, StudyPermission.EXPORT_DATA)
            val mvc = MockMvcBuilders.webAppContextSetup(child).build()
            val result = mvc.perform(get("/v3/study/$study/export/$export/download")).andReturn()
            assertEquals(403, result.response.status)
            assertTrue(AopUtils.isAopProxy(child.getBean(ExportController::class.java)))
            verifyNoInteractions(exports)
            verify(access).requirePermission(study, StudyPermission.EXPORT_DATA)
            doNothing().`when`(access).requirePermission(study, StudyPermission.EXPORT_DATA)
            assertEquals(200, mvc.perform(get("/v3/study/$study/export/$export/download")).andReturn().response.status)
            verify(exports).getExportStatusForDownload(study, export, "mvc-export-user")
        } finally { TestSecurityUtils.clearSecurityContext(); child.close(); parent.close() }
    }
}
