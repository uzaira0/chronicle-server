package com.openlattice.chronicle.temporal

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.Worker
import io.temporal.worker.WorkerFactory
import io.temporal.worker.WorkerFactoryOptions
import io.temporal.worker.WorkerOptions
import com.openlattice.chronicle.temporal.activities.NotificationActivitiesImpl
import com.openlattice.chronicle.temporal.activities.DeletionActivitiesImpl
import com.openlattice.chronicle.temporal.activities.PipelineActivitiesImpl
import com.openlattice.chronicle.temporal.workflows.NotificationWorkflowImpl
import com.openlattice.chronicle.temporal.workflows.DeletionWorkflowImpl
import com.openlattice.chronicle.temporal.workflows.PipelineWorkflowImpl
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Temporal workflow engine configuration.
 *
 * Activated by setting `chronicle.temporal.enabled=true` in application properties.
 * When disabled, the existing [com.openlattice.chronicle.services.jobs.JobService] polling
 * loop continues to process jobs as before.
 */
/**
 * Condition that checks if Temporal is enabled via system property or environment variable.
 */
public class TemporalEnabledCondition : org.springframework.context.annotation.Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        return context.environment.getProperty("chronicle.temporal.enabled", "false") == "true"
    }
}

@Configuration
@Conditional(TemporalEnabledCondition::class)
public open class ChronicleTemporalConfig {

    private val logger = LoggerFactory.getLogger(ChronicleTemporalConfig::class.java)

    public companion object {
        public const val TASK_QUEUE: String = "chronicle-jobs"
        public const val NAMESPACE: String = "chronicle"
    }

    @Bean
    public open fun workflowServiceStubs(
        @Value("\${chronicle.temporal.target:localhost:7233}") target: String,
    ): WorkflowServiceStubs {
        val options = WorkflowServiceStubsOptions.newBuilder()
            .setTarget(target)
            .build()
        return WorkflowServiceStubs.newServiceStubs(options)
    }

    @Bean
    public open fun workflowClient(stubs: WorkflowServiceStubs): WorkflowClient {
        val options = WorkflowClientOptions.newBuilder()
            .setNamespace(NAMESPACE)
            .build()
        return WorkflowClient.newInstance(stubs, options)
    }

    @Bean
    public open fun workerFactory(client: WorkflowClient): WorkerFactory {
        val options = WorkerFactoryOptions.newBuilder().build()
        return WorkerFactory.newInstance(client, options)
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public open fun chronicleWorker(
        factory: WorkerFactory,
        notificationActivities: NotificationActivitiesImpl,
        deletionActivities: DeletionActivitiesImpl,
        pipelineActivities: PipelineActivitiesImpl,
    ): WorkerFactory {
        val options = WorkerOptions.newBuilder()
            .setMaxConcurrentActivityExecutionSize(4)
            .setMaxConcurrentWorkflowTaskExecutionSize(4)
            .build()

        val worker: Worker = factory.newWorker(TASK_QUEUE, options)

        // Register workflow implementations
        worker.registerWorkflowImplementationTypes(
            NotificationWorkflowImpl::class.java,
            DeletionWorkflowImpl::class.java,
            PipelineWorkflowImpl::class.java,
        )

        // Register activity implementations (Spring-managed beans with injected dependencies)
        worker.registerActivitiesImplementations(
            notificationActivities,
            deletionActivities,
            pipelineActivities,
        )

        logger.info("Temporal worker registered on task queue '{}'", TASK_QUEUE)
        return factory
    }
}
