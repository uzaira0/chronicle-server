package com.openlattice.chronicle.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import com.geekbeast.controllers.exceptions.ForbiddenException
import com.openlattice.chronicle.audit.AuditService
import com.openlattice.chronicle.authorization.StudyAuthorizationService
import com.openlattice.chronicle.authorization.StudyPermission
import com.openlattice.chronicle.authorization.aspects.StudyAuthorizationInterceptor
import com.openlattice.chronicle.configuration.RateLimitFilter
import com.openlattice.chronicle.converters.PostgresDownloadCsvHttpMessageConverter
import com.openlattice.chronicle.converters.TimeUseDiaryDownloadCsvHttpMessageConverter
import com.openlattice.chronicle.converters.YamlHttpMessageConverter
import com.openlattice.chronicle.pods.servlet.ChronicleServerMvcPod
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import com.openlattice.chronicle.services.download.StoredExportTypes
import com.openlattice.chronicle.services.download.StudyExportAvailability
import com.openlattice.chronicle.storage.StorageResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.aop.support.AopUtils
import org.springframework.context.support.GenericApplicationContext
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.http.ResponseEntity
import org.springframework.http.MediaType
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.util.UUID

class MvcChildAuthorizationTest {
    @RestControllerAdvice
    class TestAdvice {
        @ExceptionHandler(ForbiddenException::class)
        fun forbidden(): ResponseEntity<Void> = ResponseEntity.status(403).build()
    }

    @Test
    fun mvcOwnedControllerUsesParentAuthorizationAdvisorBeforeExportAvailability() {
        val storedTypes = mock(StoredExportTypes::class.java)
        val access = mock(StudyAuthorizationService::class.java)
        val rateLimitFilter = mock(RateLimitFilter::class.java)
        val parent = GenericApplicationContext()
        parent.beanFactory.registerSingleton("storedExportTypes", storedTypes)
        parent.beanFactory.registerSingleton("audit", mock(AuditService::class.java))
        parent.beanFactory.registerSingleton("mapper", ObjectMapper())
        parent.beanFactory.registerSingleton("storageResolver", mock(StorageResolver::class.java))
        parent.beanFactory.registerSingleton("studyAccessAdvisor", StudyAuthorizationInterceptor.advisor { access })
        parent.beanFactory.registerSingleton("rateLimitFilter", rateLimitFilter)
        parent.refresh()
        val child = AnnotationConfigWebApplicationContext()
        child.parent = parent
        child.servletContext = MockServletContext()
        child.register(ChronicleServerMvcPod::class.java, TestAdvice::class.java)
        child.addBeanFactoryPostProcessor { factory ->
            val registry = factory as BeanDefinitionRegistry
            registry.beanDefinitionNames.filter { name ->
                val type = registry.getBeanDefinition(name).beanClassName.orEmpty()
                type.startsWith("com.openlattice.chronicle.controllers.") && type != ExportController::class.java.name &&
                    type != TestAdvice::class.java.name
            }.forEach(registry::removeBeanDefinition)
        }
        try {
            child.refresh()
            verify(rateLimitFilter).bindHandlerMapping(child.getBean(RequestMappingHandlerMapping::class.java))
            assertConfiguredConverters(parent, child)
            val study = UUID.randomUUID()
            TestSecurityUtils.setupSecurityContext(subject = "mvc-export-user", admin = false)
            `when`(storedTypes.availability(study)).thenReturn(StudyExportAvailability(emptyList(), timeUseDiary = false))
            doThrow(ForbiddenException("Denied")).`when`(access).requirePermission(study, StudyPermission.EXPORT_DATA)
            val mvc = MockMvcBuilders.webAppContextSetup(child).build()
            val availability = get("/v3/study/$study/export/availability")
            assertEquals(403, mvc.perform(availability).andReturn().response.status)
            assertTrue(AopUtils.isAopProxy(child.getBean(ExportController::class.java)))
            verifyNoInteractions(storedTypes)
            verify(access).requirePermission(study, StudyPermission.EXPORT_DATA)
            doNothing().`when`(access).requirePermission(study, StudyPermission.EXPORT_DATA)
            assertEquals(200, mvc.perform(availability).andReturn().response.status)
            verify(storedTypes).availability(study)
        } finally { TestSecurityUtils.clearSecurityContext(); child.close(); parent.close() }
    }
    private fun assertConfiguredConverters(
        parent: GenericApplicationContext,
        child: AnnotationConfigWebApplicationContext,
    ) {
        val converters = child.getBean(RequestMappingHandlerAdapter::class.java).messageConverters
        val jsonConverter = converters.single { it.canWrite(Map::class.java, MediaType.APPLICATION_JSON) }
        assertEquals(MappingJackson2HttpMessageConverter::class.java, jsonConverter.javaClass)
        assertSame(parent.getBean(ObjectMapper::class.java), (jsonConverter as MappingJackson2HttpMessageConverter).objectMapper)
        assertEquals(listOf(PostgresDownloadCsvHttpMessageConverter::class.java,
            TimeUseDiaryDownloadCsvHttpMessageConverter::class.java, YamlHttpMessageConverter::class.java),
            converters.takeLast(3).map { it.javaClass })
    }

}
