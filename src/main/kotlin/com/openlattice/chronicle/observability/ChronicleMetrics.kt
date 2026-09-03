/*
 * Copyright (C) 2024. Chronicle.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.openlattice.chronicle.observability

import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Histogram
import org.slf4j.LoggerFactory

/**
 * Central registry for Chronicle-specific Prometheus metrics.
 *
 * All custom metrics are prefixed with "chronicle_" and registered with the default
 * CollectorRegistry (which is scraped at /prometheus/).
 *
 * Metrics:
 * - chronicle_enrollment_total: unlabeled counter of participant enrollments
 * - chronicle_upload_total: counter of data uploads, labeled by data type
 * - chronicle_upload_bytes_total: counter of bytes uploaded, labeled by data type
 * - chronicle_api_request_duration_seconds: histogram of API request latency, labeled by endpoint and method
 * - chronicle_api_errors_total: counter of API errors, labeled by endpoint, method, and status
 *
 * Thread-safe: all Prometheus client metrics are safe for concurrent use.
 */
public object ChronicleMetrics {

    private val log = LoggerFactory.getLogger(ChronicleMetrics::class.java)

    // =========================================================================
    // ENROLLMENT METRICS
    // =========================================================================

    public val enrollmentTotal: Counter = Counter.build()
        .name("chronicle_enrollment_total")
        .help("Total number of participant enrollments")
        .register()

    // enrollmentErrors, uploadBytesTotal, and studyOperations are available for future use
    // but are not wired up to service layer yet. Uncomment when needed.
    // Future: wire these up in EnrollmentService, AppDataUploadService, StudyService

    // =========================================================================
    // UPLOAD METRICS
    // =========================================================================

    public val uploadTotal: Counter = Counter.build()
        .name("chronicle_upload_total")
        .help("Total number of data uploads")
        .labelNames("data_type")
        .register()

    public val uploadErrors: Counter = Counter.build()
        .name("chronicle_upload_errors_total")
        .help("Total number of upload errors")
        .labelNames("data_type", "error_type")
        .register()

    public val sensorMaterializationRunsTotal: Counter = Counter.build()
        .name("chronicle_sensor_materialization_runs_total")
        .help("Android sensor upload-buffer materialization runs by outcome")
        .labelNames("outcome")
        .register()

    public val sensorMaterializedSamplesTotal: Counter = Counter.build()
        .name("chronicle_sensor_materialized_samples_total")
        .help("Android sensor samples inserted from the durable upload buffer")
        .register()

    public val sensorMaterializationQuarantinedRowsTotal: Counter = Counter.build()
        .name("chronicle_sensor_materialization_quarantined_rows_total")
        .help("Malformed legacy Android sensor buffer rows preserved outside the retry queue")
        .register()

    public val sensorMaterializationDurationSeconds: Histogram = Histogram.build()
        .name("chronicle_sensor_materialization_duration_seconds")
        .help("Time spent materializing Android sensor upload-buffer batches")
        .buckets(0.05, 0.25, 1.0, 5.0, 30.0, 120.0, 600.0)
        .register()

    // =========================================================================
    // API LATENCY METRICS
    // =========================================================================

    public val apiRequestDuration: Histogram = Histogram.build()
        .name("chronicle_api_request_duration_seconds")
        .help("API request duration in seconds")
        .labelNames("endpoint", "method")
        .buckets(0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
        .register()

    public val apiErrorsTotal: Counter = Counter.build()
        .name("chronicle_api_errors_total")
        .help("Total number of API errors")
        .labelNames("endpoint", "method", "status")
        .register()

    // =========================================================================
    // API KEY & SECURITY METRICS
    // =========================================================================

    public val apiKeyUsageTotal: Counter = Counter.build()
        .name("chronicle_api_key_usage_total")
        .help("Total number of API key authentication attempts")
        .labelNames("key_prefix", "outcome")
        .register()

    public val honeyTokenTriggeredTotal: Counter = Counter.build()
        .name("chronicle_honey_token_triggered_total")
        .help("Total number of honey token (canary API key) uses. Any increment indicates potential unauthorized access.")
        .labelNames("key_name", "source_ip")
        .register()

    public val apiKeySourceIpHash: io.prometheus.client.Gauge = io.prometheus.client.Gauge.build()
        .name("chronicle_api_key_source_ip_hash")
        .help("Tracks distinct source IP hashes per API key prefix for anomaly detection")
        .labelNames("key_prefix")
        .register()

    public val participantFormAccessTotal: Counter = Counter.build()
        .name("chronicle_participant_form_access_total")
        .help("Participant capability-session decisions by form kind and outcome")
        .labelNames("form_kind", "outcome")
        .register()

    public val participantFormSubmissionTotal: Counter = Counter.build()
        .name("chronicle_participant_form_submission_total")
        .help("Participant form submissions by form kind and idempotency outcome")
        .labelNames("form_kind", "outcome")
        .register()

    public val dataDeletionOperationsTotal: Counter = Counter.build()
        .name("chronicle_data_deletion_operations_total")
        .help("Durable deletion operations by mode and lifecycle outcome")
        .labelNames("mode", "outcome")
        .register()

    public val dataDeletionOperationDurationSeconds: Histogram = Histogram.build()
        .name("chronicle_data_deletion_operation_duration_seconds")
        .help("Time spent in one verified deletion attempt")
        .labelNames("mode", "outcome")
        .buckets(0.05, 0.25, 1.0, 5.0, 30.0, 120.0, 600.0)
        .register()

    public val dataDeletionRetentionHoldsTotal: Counter = Counter.build()
        .name("chronicle_data_deletion_retention_holds_total")
        .help("Explicit retention hold lifecycle actions")
        .labelNames("action")
        .register()

    public val dataQualityEvaluationsTotal: Counter = Counter.build()
        .name("chronicle_data_quality_evaluations_total")
        .help("Data-quality evaluation runs by trigger and outcome")
        .labelNames("trigger", "outcome")
        .register()

    public val dataQualityAlertsTotal: Counter = Counter.build()
        .name("chronicle_data_quality_alerts_total")
        .help("New deduplicated local data-quality alerts")
        .register()

    public val exportArtifactBytes: Gauge = Gauge.build()
        .name("chronicle_export_artifact_bytes")
        .help("Bytes currently occupied by managed export artifacts")
        .register()

    public val exportStorageUsableBytes: Gauge = Gauge.build()
        .name("chronicle_export_storage_usable_bytes")
        .help("Usable bytes reported by the filesystem containing managed export artifacts")
        .register()

    public val exportStorageAdmissionRejectionsTotal: Counter = Counter.build()
        .name("chronicle_export_storage_admission_rejections_total")
        .help("Exports rejected before writing because storage capacity safeguards would be exceeded")
        .labelNames("reason")
        .register()

    public val exportJobsTotal: Counter = Counter.build()
        .name("chronicle_export_jobs_total")
        .help("Export background-job transitions by finite outcome")
        .labelNames("outcome")
        .register()

    // =========================================================================
    // STUDY METRICS
    // =========================================================================

    // =========================================================================
    // ACTIVE REQUESTS (gauge-like via concurrent counter)
    // =========================================================================

    public val activeRequests: io.prometheus.client.Gauge = io.prometheus.client.Gauge.build()
        .name("chronicle_active_requests")
        .help("Number of currently active HTTP requests")
        .register()

    init {
        log.info("Chronicle Prometheus metrics registered")
    }
}
