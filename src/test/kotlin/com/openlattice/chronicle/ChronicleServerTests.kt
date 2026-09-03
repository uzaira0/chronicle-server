package com.openlattice.chronicle

import com.hazelcast.core.HazelcastInstance
import com.geekbeast.rhizome.configuration.ConfigurationConstants
import com.geekbeast.rhizome.core.RhizomeApplicationServer
import com.geekbeast.rhizome.hazelcast.serializers.RhizomeUtils
import com.openlattice.chronicle.constants.ChronicleProfiles
import com.openlattice.chronicle.storage.PostgresDataTables
import com.openlattice.chronicle.storage.StorageResolver
import com.geekbeast.jdbc.DataSourceManager
import com.geekbeast.postgres.PostgresPod
import com.geekbeast.rhizome.configuration.websockets.BaseRhizomeServer
import com.openlattice.chronicle.mapstores.MapstoresPod
import com.openlattice.chronicle.authorization.Principal
import com.openlattice.chronicle.authorization.PrincipalType
import com.openlattice.chronicle.authorization.SecurablePrincipal
import com.openlattice.chronicle.authorization.SortedPrincipalSet
import com.openlattice.chronicle.authorization.SystemRole
import com.openlattice.chronicle.authorization.AuthorizationManager
import com.openlattice.chronicle.authorization.Permission
import com.openlattice.chronicle.authorization.principals.Principals
import com.openlattice.chronicle.authorization.principals.SecurePrincipalsManager
import com.openlattice.chronicle.client.ChronicleClient
import com.openlattice.chronicle.hazelcast.HazelcastMap
import com.openlattice.chronicle.users.ConfiguredUserListingService
import com.zaxxer.hikari.HikariDataSource
import com.openlattice.chronicle.contract.ChronicleContractTestSchema
import org.junit.Before
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.TreeSet
import java.util.UUID

open class ChronicleServerTests {
    @Before
    fun restoreIntegrationSecurityState() {
        restorePrincipalMaps()
        seedTestPrincipals(
            testServer.context.getBean(SecurePrincipalsManager::class.java),
            testServer.context.getBean(AuthorizationManager::class.java)
        )
    }

    companion object {
        @JvmField
        val testHttpPort: Int = System.getenv("CHRONICLE_TEST_HTTP_PORT")
            ?.toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: 40320
        @JvmField
        internal val testHazelcastPort: Int = 10_000 + (testHttpPort % 50_000)
        private val testHazelcastGroup = "chronicle-test-$testHttpPort"

        private val postgres = ChronicleContractTestSchema.prodPostgresContainer("chronicle").apply {
            withUsername("oltest")
            withPassword("test")
        }

        private val LOCAL_TEST_PROFILES = arrayOf(
            ConfigurationConstants.Profiles.LOCAL_CONFIGURATION_PROFILE,
            PostgresDataTables.POSTGRES_DATA_ENVIRONMENT,
            PostgresPod.PROFILE,
            ChronicleProfiles.MEDIA_LOCAL_PROFILE)

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

        init {
            postgres.start()
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
            hds = testServer.context.getBean(HikariDataSource::class.java)
            sr = testServer.context.getBean(StorageResolver::class.java)
            dsm = testServer.context.getBean(DataSourceManager::class.java)
            jwtTokens = testServer.context.getBean(ConfiguredUserListingService::class.java).jwtTokens
            ensureAuditLogsTable(hds)
            ensureRlsAppRole(hds)
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
                    java.util.EnumSet.allOf(Permission::class.java)
                )
            }
            spm.addPrincipalToPrincipal(spm.lookup(Principal(PrincipalType.USER, "test_admin")), spm.lookup(SystemRole.ADMIN.principal))
            seedResolvedPrincipalTrees()
        }

        /**
         * Mirror docker/init-db-roles.sql in the test database: the request-path RLS
         * wrapper drops to the non-superuser `chronicle_app` role via SET ROLE, so the
         * role must exist and hold DML on every table or any authenticated request would
         * fail. Runs after the schema + audit_logs are created so the blanket grants and
         * default privileges (for tables created later by oltest) cover everything.
         */
        private fun ensureRlsAppRole(hds: HikariDataSource) {
            hds.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        DO ${'$'}${'$'}
                        BEGIN
                            IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'chronicle_app') THEN
                                CREATE ROLE chronicle_app WITH LOGIN NOSUPERUSER NOCREATEDB
                                    NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
                            END IF;
                        END ${'$'}${'$'};
                        """.trimIndent()
                    )
                    statement.execute("GRANT USAGE ON SCHEMA public TO chronicle_app")
                    statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO chronicle_app")
                    statement.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO chronicle_app")
                    statement.execute("GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO chronicle_app")
                    statement.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO chronicle_app")
                    statement.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO chronicle_app")
                    statement.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO chronicle_app")
                    statement.execute(
                        "REVOKE UPDATE, DELETE, TRUNCATE ON audit_logs, study_settings_audit, " +
                            "participant_collection_acknowledgment, mobile_withdrawal_requests FROM chronicle_app"
                    )
                    statement.execute(
                        "REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON " +
                            "data_collection_settings_revisions FROM chronicle_app"
                    )
                    statement.execute(
                        "REVOKE ALL ON restore_continuity_reconciliations FROM chronicle_app"
                    )
                    statement.execute(
                        "REVOKE EXECUTE ON FUNCTION record_data_collection_settings_revision() FROM chronicle_app"
                    )
                    statement.execute("REVOKE CREATE ON SCHEMA public FROM chronicle_app")
                }
            }
        }

        private fun ensureAuditLogsTable(hds: HikariDataSource) {
            hds.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE IF NOT EXISTS audit_logs (
                            id UUID PRIMARY KEY,
                            timestamp TIMESTAMPTZ NOT NULL,
                            user_id TEXT,
                            user_role TEXT,
                            ip_address TEXT,
                            user_agent TEXT,
                            action TEXT NOT NULL,
                            resource_type TEXT,
                            resource_id TEXT,
                            study_id UUID,
                            organization_id UUID,
                            success BOOLEAN NOT NULL,
                            error_message TEXT,
                            accessed_phi BOOLEAN NOT NULL DEFAULT false,
                            phi_fields TEXT[],
                            request_path TEXT,
                            request_method TEXT,
                            response_code INTEGER,
                            duration_ms BIGINT,
                            additional_data JSONB
                        )
                        """.trimIndent()
                    )
                }
            }
        }

        private fun restorePrincipalMaps() {
            val securableField = Principals::class.java.getDeclaredField("securablePrincipals")
            securableField.isAccessible = true
            securableField.set(null, HazelcastMap.SECURABLE_PRINCIPALS.getMap(hazelcastInstance))

            val principalsField = Principals::class.java.getDeclaredField("principals")
            principalsField.isAccessible = true
            principalsField.set(null, HazelcastMap.RESOLVED_PRINCIPAL_TREES.getMap(hazelcastInstance))
        }

        @Suppress("UNCHECKED_CAST")
        private fun seedResolvedPrincipalTrees() {
            val field = Principals::class.java.getDeclaredField("principals")
            field.isAccessible = true
            val principalsMap = field.get(null) as com.hazelcast.map.IMap<String, SortedPrincipalSet>
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

        // reason: length is dominated by three embedded YAML config literals (rhizome/jetty/auth)
        // written to the test classpath; extracting them would only obscure the test fixture
        @Suppress("LongMethod")
        private fun writeTestConfig() {
            val jdbcUrl = postgres.jdbcUrl
            val yaml = """
                enable-persistence: false
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
                    hikari:
                        jdbcUrl: "$jdbcUrl"
                        username: "oltest"
                        password: "test"
                        maximumPoolSize: 10
                datasources:
                    chronicle:
                        citus: false
                        initialize-tables: true
                        hikari:
                            jdbcUrl: "$jdbcUrl"
                            username: "oltest"
                            password: "test"
                            maximumPoolSize: 10
                    platform_read:
                        citus: false
                        initialize-tables: false
                        hikari:
                            jdbcUrl: "$jdbcUrl"
                            username: "oltest"
                            password: "test"
                            maximumPoolSize: 10
            """.trimIndent()

            // Write to build output classpath so rhizome picks it up without dirtying source tree
            val configDir = File("build/resources/test")
            configDir.mkdirs()
            File(configDir, "rhizome.yaml").writeText(yaml)
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
                    certificate-alias: rhizomessl
                keymanager-password: rhizome
                keystore:
                    path: security/rhizome.jks
                    password: rhizome
                truststore:
                    path: security/rhizome.jks
                    password: rhizome
                gzip:
                    enabled: true
                security-enabled: true
                """.trimIndent()
            )

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
        val clientUser1 = ChronicleClient { jwtTokens.getValue(testUser1.id).first() }
        @JvmField
        val clientUser2 = ChronicleClient { jwtTokens.getValue(testUser2.id).first() }
        @JvmField
        val clientUser3 = ChronicleClient { jwtTokens.getValue(testUser3.id).first() }
        @JvmField
        val clientAdmin = ChronicleClient { jwtTokens.getValue(adminUser.id).first() }
    }
}
