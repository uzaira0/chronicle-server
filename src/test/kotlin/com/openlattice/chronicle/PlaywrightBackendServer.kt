package com.openlattice.chronicle

import org.junit.Test
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Long-running test server for Playwright e2e tests.
 *
 * Boots the full Chronicle server (testcontainer Postgres + embedded Jetty on the configured
 * `CHRONICLE_TEST_HTTP_PORT`, defaulting to 40320)
 * and blocks indefinitely so the operator can run frontend tests against it.
 *
 * Run with:
 *   ./gradlew :chronicle-server:test --tests "*PlaywrightBackendServer*" -Dchronicle.playwright.backend=true
 *
 * Interactive mode still waits until the Gradle process is interrupted. Automation should pass
 * `chronicle.playwright.backend.doneFile` and `chronicle.playwright.backend.maxWaitSeconds`; the
 * harness then exits naturally when the absent completion file is created or fails at the bound.
 */
class PlaywrightBackendServer : ChronicleServerTests() {
    private val logger = LoggerFactory.getLogger(PlaywrightBackendServer::class.java)

    @Test
    fun bootAndBlock() {
        if (System.getProperty("chronicle.playwright.backend") != "true") {
            logger.info("Skipping Playwright backend harness; pass -Dchronicle.playwright.backend=true to block.")
            return
        }
        check(hazelcastInstance.cluster.members.size == 1) {
            "Playwright backend must use an isolated one-member Hazelcast cluster; found " +
                    "${hazelcastInstance.cluster.members.size} members"
        }
        check(hazelcastInstance.cluster.localMember.address.port == testHazelcastPort) {
            "Playwright backend Hazelcast port auto-incremented from $testHazelcastPort to " +
                    hazelcastInstance.cluster.localMember.address.port
        }
        logger.info("===== Chronicle test backend ready on http://localhost:{} =====", testHttpPort)
        logger.info("===== Test users: test_user1, test_user2, test_user3, test_admin =====")

        val doneFileValue = System.getProperty("chronicle.playwright.backend.doneFile")
        if (doneFileValue.isNullOrBlank()) {
            logger.info("===== Interactive mode: interrupt Gradle to shut down =====")
            CountDownLatch(1).await()
            return
        }

        val doneFile = Path.of(doneFileValue)
        require(doneFile.isAbsolute) {
            "chronicle.playwright.backend.doneFile must be an absolute path"
        }
        require(!Files.exists(doneFile, NOFOLLOW_LINKS)) {
            "chronicle.playwright.backend.doneFile must not exist before the backend is ready"
        }
        val doneParent = requireNotNull(doneFile.parent) {
            "chronicle.playwright.backend.doneFile must have a parent directory"
        }
        require(Files.isDirectory(doneParent, NOFOLLOW_LINKS)) {
            "chronicle.playwright.backend.doneFile parent must be a real directory"
        }

        val maxWaitSeconds = System.getProperty(
            "chronicle.playwright.backend.maxWaitSeconds",
            "900",
        ).toLongOrNull()
        require(maxWaitSeconds != null && maxWaitSeconds in 1..3_600) {
            "chronicle.playwright.backend.maxWaitSeconds must be an integer from 1 to 3600"
        }

        logger.info(
            "===== Bounded mode: waiting at most {} seconds for completion file {} =====",
            maxWaitSeconds,
            doneFile,
        )
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(maxWaitSeconds)
        while (!Files.exists(doneFile, NOFOLLOW_LINKS)) {
            check(System.nanoTime() < deadline) {
                "Timed out waiting for Playwright completion file"
            }
            Thread.sleep(250)
        }
        check(Files.isRegularFile(doneFile, NOFOLLOW_LINKS)) {
            "Playwright completion marker must be a regular file"
        }
        logger.info("===== Playwright completion file received; shutting down naturally =====")
    }
}
