package com.openlattice.chronicle

import com.geekbeast.mappers.mappers.ObjectMappers
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.geekbeast.rhizome.configuration.websockets.BaseRhizomeServer
import com.geekbeast.rhizome.core.RhizomeApplicationServer
import com.geekbeast.rhizome.hazelcast.serializers.RhizomeUtils.Pods
import com.geekbeast.rhizome.pods.hazelcast.RegistryBasedHazelcastInstanceConfigurationPod
import com.openlattice.chronicle.hazelcast.pods.SharedStreamSerializersPod
import com.openlattice.chronicle.mapstores.MapstoresPod
import com.openlattice.chronicle.pods.ChronicleConfigurationPod
import com.openlattice.chronicle.pods.ChronicleJobRunnersPod
import com.openlattice.chronicle.pods.ChronicleServerServicesPod
import com.openlattice.chronicle.pods.ChronicleServerServletsPod
import com.openlattice.chronicle.pods.RLSSecurityPod
import com.openlattice.chronicle.pods.StudyAuthorizationPod
import com.openlattice.chronicle.serializers.FullQualifiedNameJacksonSerializer
import com.openlattice.chronicle.storage.pods.ByteBlobServicePod
import com.openlattice.ioc.providers.LateInitProvidersPod
import com.geekbeast.jdbc.JdbcPod
import com.geekbeast.postgres.PostgresPod
import com.geekbeast.pods.TaskSchedulerPod
import com.geekbeast.rhizome.configuration.ConfigurationConstants
import com.openlattice.chronicle.constants.ChronicleProfiles
import com.openlattice.chronicle.hazelcast.pods.HazelcastQueuePod
import com.openlattice.chronicle.pods.servlet.ChronicleServerSecurityPod
import com.openlattice.chronicle.pods.tables.PostgresDataTablesPod
import com.openlattice.chronicle.pods.tables.PostgresTablesPod
import com.openlattice.chronicle.pods.tables.PostgresEventTablesPod
import com.openlattice.chronicle.storage.PostgresDataTables

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
// reason: vararg pod/profile APIs require spread; the arrays are small and assembled once at startup
@Suppress("SpreadOperator")
public open class ChronicleServer(vararg pods: Class<*>) : BaseRhizomeServer(
        *Pods.concatenate(
            pods,
            webPods,
            rhizomePods,
            RhizomeApplicationServer.DEFAULT_PODS,
            chronicleServerPods
        )
) {
    internal companion object {
        public val webPods = arrayOf(
            ChronicleServerServletsPod::class.java,
            ChronicleServerSecurityPod::class.java
        )
        public val rhizomePods = arrayOf(
            MapstoresPod::class.java,
            RegistryBasedHazelcastInstanceConfigurationPod::class.java,
        )
        public val chronicleServerPods = arrayOf(
            ChronicleConfigurationPod::class.java,
            JdbcPod::class.java,
            ChronicleServerServicesPod::class.java,
            StudyAuthorizationPod::class.java,
            PostgresPod::class.java,
            PostgresTablesPod::class.java,
            PostgresEventTablesPod::class.java,
            PostgresDataTablesPod::class.java,
            TaskSchedulerPod::class.java,
            SharedStreamSerializersPod::class.java,
            ByteBlobServicePod::class.java,
            LateInitProvidersPod::class.java,
            HazelcastQueuePod::class.java,
            ChronicleJobRunnersPod::class.java,
            RLSSecurityPod::class.java,
        )

        private val LOCAL_TEST_PROFILES = arrayOf(
            ConfigurationConstants.Profiles.LOCAL_CONFIGURATION_PROFILE,
            PostgresDataTables.POSTGRES_DATA_ENVIRONMENT,
            PostgresPod.PROFILE,
            ChronicleProfiles.MEDIA_LOCAL_PROFILE)


        @Throws(Exception::class)
        @JvmStatic
        public fun main(args: Array<String>) {
            val chronicleServer = ChronicleServer()
            chronicleServer.start(*LOCAL_TEST_PROFILES)
        }

        init {
            ObjectMappers.foreach { mapper: ObjectMapper ->
                FullQualifiedNameJacksonSerializer.registerWithMapper(
                        mapper
                )
            }
            ObjectMappers.foreach { mapper: ObjectMapper ->
                mapper.disable(
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS
                )
            }
        }
    }

    @Throws(Exception::class)
    override fun start(vararg profiles: String) {
        super.start(*profiles)
    }
}
