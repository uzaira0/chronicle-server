package com.openlattice.chronicle

import com.auth0.jwt.JWT
import com.geekbeast.jdbc.DataSourceManager
import com.geekbeast.mappers.mappers.ObjectMappers
import com.geekbeast.postgres.PostgresPod
import com.geekbeast.rhizome.configuration.ConfigurationConstants
import com.geekbeast.rhizome.configuration.websockets.BaseRhizomeServer
import com.geekbeast.rhizome.core.RhizomeApplicationServer
import com.geekbeast.rhizome.hazelcast.serializers.RhizomeUtils
import com.hazelcast.core.HazelcastInstance
import com.openlattice.chronicle.auditing.AuditEventType
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.CreatorPrincipalResolver
import com.openlattice.chronicle.authorization.JwtBlocklist
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.SecurablePrincipal
import com.openlattice.chronicle.authorization.SortedPrincipalSet
import com.openlattice.chronicle.authorization.SystemRole
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.authorization.principals.SecurePrincipalsManager
import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.client.RetrofitClientFactory
import com.openlattice.chronicle.configuration.MobileSecurityConfiguration
import com.openlattice.chronicle.configuration.MobileApiSignatureFilterTest
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.mapstores.MapstoresPod
import com.openlattice.chronicle.storage.PostgresDataTables
import com.openlattice.chronicle.storage.StorageResolver
import com.openlattice.chronicle.users.ConfiguredUserListingService
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.HikariConfig
import java.io.File
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.EnumSet
import java.util.Optional
import java.util.TreeSet
import java.util.UUID
import org.junit.Before
import org.junit.experimental.categories.Category

@Category(ChronicleServerTests::class)
open class ChronicleServerTests {
    @Before
    fun restoreIntegrationSecurityState() {
        restorePrincipalContext()
        seedTestPrincipals(
            testServer.context.getBean(SecurePrincipalsManager::class.java),
            testServer.context.getBean(AuthorizationManager::class.java)
        )
    }

    companion object {
        @JvmField
        val testHttpPort: Int = RetrofitClientFactory.testingHttpPort
        /** Asked of the OS, never derived from the HTTP port: a derived port can sit in the ephemeral range under someone else's connection. */
        @JvmField
        internal val testHazelcastPort: Int = ServerSocket(0).use { it.localPort }
        private val testHazelcastGroup = "chronicle-test-$testHttpPort"

        private val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle").apply {
            withUsername("oltest")
            withPassword("test")
        }

        private val LOCAL_TEST_PROFILES = arrayOf(
            ConfigurationConstants.Profiles.LOCAL_CONFIGURATION_PROFILE,
            PostgresDataTables.POSTGRES_DATA_ENVIRONMENT,
            PostgresPod.PROFILE)

        /*
         * BaseRhizomeServer uses a plain Spring environment rather than Spring Boot,
         * so src/test/resources/application.properties is not an automatic property
         * source. Install the synthetic-only security overrides in the actual
         * system-property source before the security pod and test server are
         * constructed. MFA behavior remains covered by the dedicated validator
         * and resource-server matrix rather than by these legacy fixture tokens.
         */
        init {
            System.setProperty(
                "chronicle.security.require-mfa",
                "false",
            )
            System.setProperty(
                "chronicle.security.metrics-password",
                "chronicle-test-metrics-password-never-for-production",
            )
        }

        @JvmField
        val testServer = BaseRhizomeServer(
            *RhizomeUtils.Pods.concatenate(
                ChronicleServer.webPods,
                arrayOf(
                    MapstoresPod::class.java,
                    FailFastTestHazelcastConfigurationPod::class.java,
                    RateLimitTestClockPod::class.java,
                ),
                RhizomeApplicationServer.DEFAULT_PODS,
                ChronicleServer.chronicleServerPods
            )
        )

        @JvmField
        val hazelcastInstance: HazelcastInstance

        @JvmField
        val hds: HikariDataSource

        @JvmField
        val sr: StorageResolver

        @JvmField
        val dsm: DataSourceManager

        @JvmField
        val jwtTokens : Map<String,List<String>>

        /** Committed rows of one audit event type for a study, as the append-only `audit` table holds them. */
        fun auditEventCount(studyId: UUID, type: AuditEventType): Int = hds.connection.use { connection ->
            connection.prepareStatement("SELECT count(*) FROM audit WHERE study_id = ? AND audit_event_type = ?").use {
                it.setString(1, studyId.toString())
                it.setString(2, type.name)
                it.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
            }
        }

        init {
            postgres.start()
            ChronicleContractTestSchema.applyFrameworkSchemaAndMigrations(postgres)
            writeTestConfig()
            testServer.start(*LOCAL_TEST_PROFILES)

            hazelcastInstance = testServer.context.getBean(HazelcastInstance::class.java)
            check(!hazelcastInstance.config.networkConfig.isPortAutoIncrement) {
                "Chronicle tests must fail rather than auto-incrementing the isolated Hazelcast port"
            }
            check(hazelcastInstance.cluster.localMember.address.port == testHazelcastPort) {
                "Chronicle test Hazelcast member did not bind the configured port $testHazelcastPort"
            }
            check(hazelcastInstance.cluster.members.size == 1) {
                "Chronicle tests require one isolated Hazelcast member"
            }
            sr = testServer.context.getBean(StorageResolver::class.java)
            hds = HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = ChronicleContractTestSchema.BOOTSTRAP_MAXIMUM_POOL_SIZE
                minimumIdle = 0
            })
            dsm = testServer.context.getBean(DataSourceManager::class.java)
            jwtTokens = testServer.context.getBean(ConfiguredUserListingService::class.java).jwtTokens
            seedTestPrincipals(
                testServer.context.getBean(SecurePrincipalsManager::class.java),
                testServer.context.getBean(AuthorizationManager::class.java)
            )
        }

        private fun seedTestPrincipals(spm: SecurePrincipalsManager, authorizationManager: AuthorizationManager) {
            val users = listOf(
                Principal(PrincipalType.USER, "test_user1"),
                Principal(PrincipalType.USER, "test_user2"),
                Principal(PrincipalType.USER, "test_user3"),
                Principal(PrincipalType.USER, "test_admin")
            )
            users.forEach { principal ->
                spm.createSecurablePrincipalIfNotExists(
                    SecurablePrincipal(
                        Optional.of(UUID.nameUUIDFromBytes("chronicle-test:${principal.id}".toByteArray(StandardCharsets.UTF_8))),
                        principal,
                        principal.id,
                        Optional.of("${principal.id}@chronicle.test")
                    )
                )
                authorizationManager.addPermission(
                    spm.lookup(principal),
                    SystemRole.adminRole,
                    EnumSet.allOf(Permission::class.java)
                )
            }
            spm.addPrincipalToPrincipal(spm.lookup(Principal(PrincipalType.USER, "test_admin")), spm.lookup(SystemRole.ADMIN.principal))
            seedResolvedPrincipalTrees()
        }

        private fun restorePrincipalContext() = Principals.init(
            testServer.context.getBean(SecurePrincipalsManager::class.java),
            hazelcastInstance,
            testServer.context.getBean(CreatorPrincipalResolver::class.java),
        )

        private fun seedResolvedPrincipalTrees() {
            val principalsMap = HazelcastMap.RESOLVED_PRINCIPAL_TREES.getMap(hazelcastInstance)
            listOf("test_user1", "test_user2", "test_user3").forEach { userId ->
                principalsMap[userId] = SortedPrincipalSet(TreeSet<Principal>().apply {
                    add(Principal(PrincipalType.USER, userId))
                })
            }
            principalsMap["test_admin"] = SortedPrincipalSet(TreeSet<Principal>().apply {
                add(Principal(PrincipalType.USER, "test_admin"))
                add(SystemRole.adminRole)
            })
        }

        private fun writeTestConfig() {
            // Write to the build output classpath without dirtying the source tree.
            val configDir = File("build/resources/test")
            configDir.mkdirs()
            writeRhizomeTestConfig(configDir)
            writeJettyTestConfig(configDir)
            writeAuthenticationTestConfig(configDir)
            ObjectMappers.getJsonMapper().writeValue(File(configDir, MobileSecurityConfiguration.key.uri),
                MobileSecurityConfiguration(internalWebSecret = System.getenv("CHRONICLE_INTERNAL_WEB_SECRET")
                    ?.takeIf { it.isNotBlank() } ?: MobileApiSignatureFilterTest.SECRET))
        }

        private fun writeRhizomeTestConfig(configDir: File) {
            val jdbcUrl = postgres.jdbcUrl
            val runtimePool = ObjectMappers.getJsonMapper().writeValueAsString(mapOf(
                "jdbcUrl" to jdbcUrl, "username" to ChronicleContractTestSchema.RUNTIME_USERNAME,
                "password" to ChronicleContractTestSchema.RUNTIME_PASSWORD, "maximumPoolSize" to 10,
            ))
            val yaml = """
                enable-persistence: true
                session-clustering-enabled: false
                hazelcast:
                    server: true
                    group: "$testHazelcastGroup"
                    password: "trellis"
                    cp-member-count: 0
                    seed-nodes:
                        - "127.0.0.1:$testHazelcastPort"
                    port: $testHazelcastPort
                hazelcast-clients:
                    IDS:
                        server: false
                        group: "$testHazelcastGroup"
                        password: "trellis"
                        cp-member-count: 0
                        seed-nodes:
                            - "127.0.0.1:$testHazelcastPort"
                postgres:
                    citus: false
                    initialize-tables: false
                    initialize-indices: false
                    hikari: $runtimePool
                datasources:
                    chronicle:
                        citus: false
                        initialize-tables: false
                        initialize-indices: false
                        hikari: $runtimePool
                    platform_read:
                        citus: false
                        initialize-tables: false
                        initialize-indices: false
                        hikari: $runtimePool
            """.trimIndent()

            File(configDir, "rhizome.yaml").writeText(yaml)
        }

        private fun writeJettyTestConfig(configDir: File) {
            File(configDir, "jetty.yaml").writeText(
                """
                max-threads: 200
                context:
                    descriptor: src/main/webapp/WEB-INF/web.xml
                    resource-base: src/main/webapp
                    path: /
                    parent-loader-priority: true
                web-endpoint:
                    http-port: $testHttpPort
                    https-port: 8443
                    use-ssl: false
                    require-ssl: false
                    require-client-auth: false
                    want-client-auth: false
                gzip:
                    enabled: true
                security-enabled: true
                """.trimIndent()
            )
        }

        private fun writeAuthenticationTestConfig(configDir: File) {
            val authYaml = """
                testingLoginEnabled: true
                defaultTestingUserId: "test_user1"
                configurations:
                  - issuer: "https://localhost/"
                    audience: "chronicle-test-client"
                    secret: "chronicle-e2e-test-secret-key-minimum-256-bits"
                    base64EncodedSecret: false
                    signingAlgorithm: "HS256"
                users:
                  - user_id: "test_user1"
                    email: "user1@chronicle.test"
                    email_verified: true
                  - user_id: "test_user2"
                    email: "user2@chronicle.test"
                    email_verified: true
                  - user_id: "test_user3"
                    email: "user3@chronicle.test"
                    email_verified: true
                  - user_id: "test_admin"
                    email: "admin@chronicle.test"
                    email_verified: true
            """.trimIndent()
            File(configDir, "chronicle-auth.yaml").writeText(authYaml)
        }

        @JvmField
        val testUser1 = Principal(PrincipalType.USER, "test_user1")
        @JvmField
        val testUser2 = Principal(PrincipalType.USER, "test_user2")
        @JvmField
        val testUser3 = Principal(PrincipalType.USER, "test_user3")
        @JvmField
        val adminUser = Principal(PrincipalType.USER, "test_admin")

        @JvmField
        val clientUser1 = ChronicleClient { activeFixtureToken(testUser1) }
        @JvmField
        val clientUser2 = ChronicleClient { activeFixtureToken(testUser2) }
        @JvmField
        val clientUser3 = ChronicleClient { activeFixtureToken(testUser3) }
        @JvmField
        val clientAdmin = ChronicleClient { activeFixtureToken(adminUser) }

        internal fun activeFixtureToken(user: Principal): String {
            val token = jwtTokens.getValue(user.id).first()
            val jwt = JWT.decode(token)
            val blocklist = testServer.context.getBean(JwtBlocklist::class.java)
            if (blocklist.credentialRejectionReason(jwt.id, token, jwt.issuedAt?.toInstant(), jwt.expiresAt?.toInstant(),
                    Duration.ofMinutes(15), subject = user.id) == null) return token

            // JWT iat has second precision; reauthenticate after the ACL cutoff's second.
            val nextSecond = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1)
            while (Instant.now().isBefore(nextSecond)) {
                Thread.sleep(maxOf(1, Duration.between(Instant.now(), nextSecond).toMillis()))
            }
            return requireNotNull(testServer.context.getBean(ConfiguredUserListingService::class.java).issueDashboardToken(user.id))
        }
    }
}
