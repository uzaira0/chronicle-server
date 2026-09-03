package com.openlattice.chronicle.tasks

import com.geekbeast.tasks.HazelcastInitializationTask
import com.geekbeast.tasks.HazelcastTaskDependencies
import com.openlattice.chronicle.services.delete.RestoreContinuityReconciler

public class RestoreContinuityInitializationDependencies(
    internal val reconciler: RestoreContinuityReconciler,
) : HazelcastTaskDependencies

/** Blocks server startup until a guarded-restore checkpoint is fully reconciled. */
public class RestoreContinuityInitializationTask :
    HazelcastInitializationTask<RestoreContinuityInitializationDependencies> {
    override fun getInitialDelay(): Long = 0

    override fun initialize(dependencies: RestoreContinuityInitializationDependencies) {
        dependencies.reconciler.reconcile()
    }

    override fun after(): Set<Class<out HazelcastInitializationTask<*>>> =
        setOf(PostConstructInitializerTaskDependencies.PostConstructInitializerTask::class.java)

    override fun getName(): String = "RESTORE_CONTINUITY_RECONCILIATION"

    override fun getDependenciesClass(): Class<out RestoreContinuityInitializationDependencies> =
        RestoreContinuityInitializationDependencies::class.java

    // Every node blocks, while the PostgreSQL session lock serializes the work. Avoid a
    // durable failed Hazelcast future that would otherwise poison every restart after an
    // operator repairs a checkpoint conflict.
    override fun isRunOnceAcrossCluster(): Boolean = false
}
