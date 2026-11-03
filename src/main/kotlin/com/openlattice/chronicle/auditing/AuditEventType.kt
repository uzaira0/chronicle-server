/*
 * Copyright (C) 2019. OpenLattice, Inc.
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
 *
 * You can contact the owner of the copyright at support@openlattice.com
 *
 *
 */

package com.openlattice.chronicle.auditing

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
public enum class AuditEventType {
    ACCESS_DENIED,
    ALLOW_APPS,
    AUTHORIZATION_CHECK_FAILED,
    ADD_PERMISSION,
    ASSOCIATE_STUDY,
    CREATE_API_KEY,
    CREATE_JOB,
    CREATE_ORGANIZATION,
    CREATE_QUESTIONNAIRE,
    CREATE_STUDY,
    REVOKE_API_KEY,
    REVOKE_PARTICIPANT_ACCESS_CODE,
    DELETE_QUESTIONNAIRE,
    DELETE_STUDY,
    DELETE_PARTICIPANTS,
    DOWNLOAD_TIME_USE_DIARY_DATA,
    DOWNLOAD_PARTICIPANTS_TIME_USE_DIARY_DATA,
    DOWNLOAD_PARTICIPANTS_DATA,
    ENROLL_DEVICE,
    FILTER_APPS,
    GET_FILTERED_APPS,
    GET_ORGANIZATION,
    GET_STUDY,
    GET_TIME_USE_DIARY_SUBMISSION,
    REGISTER_CANDIDATE,
    REMOVE_PERMISSION,
    SET_PERMISSION,
    STUDY_NOT_FOUND,
    SUBMIT_TIME_USE_DIARY,
    UPDATE_PARTICIPANT_ANNOTATIONS,
    UPDATE_PARTICIPATION_STATUS,
    PARTICIPANT_PROCESSING_BLOCK,
    PARTICIPANT_PROCESSING_UNBLOCK,
    UPDATE_QUESTIONNAIRE,
    UPDATE_STUDY,
    UPDATE_STUDY_SETTINGS,
    QUEUE_NOTIFICATIONS,
    GET_ALL_STUDIES,
    NOTIFICATION_SENT,
    SET_STUDY_LIMITS,
    TRIGGER_STUDY_COMPLIANCE_NOTIFICATIONS,
    ARCHIVE_STUDY,
    UNARCHIVE_STUDY,
    CLONE_STUDY,
    SCHEDULE_STUDY_DELETION,
    CANCEL_SCHEDULED_DELETION,
    CANCEL_DATA_DELETION,
    PREVIEW_PARTICIPANT_PURGE,
    PURGE_PARTICIPANT_DATA,
    START_DATA_DELETION,
    VERIFY_DATA_DELETION,
    TRIGGER_PIPELINE,
    GET_PIPELINE_RUNS,

    // Request-level events recorded through AuditService
    LOGIN,
    LOGIN_FAILED,
    LOGOUT,
    VIEW,
    LIST,
    DOWNLOAD,
    EXPORT,
    CREATE,
    UPDATE,
    DELETE,
    SETTINGS_CHANGE,
    CONFIGURATION_CHANGE,
    PERMISSION_CHANGE,
    PERMISSION_GRANT,
    PERMISSION_REVOKE,
    UNAUTHORIZED_ACCESS,
    INVALID_REQUEST,
    DATA_SUBMISSION,
    DEVICE_ENROLLMENT,
    SENSOR_DATA_UPLOAD,
    SCREEN_TIME_UPLOAD,
    USAGE_DATA_UPLOAD,
    BATTERY_TELEMETRY_UPLOAD,
    INTERACTION_EVENTS_UPLOAD,
    COLLECTION_ACKNOWLEDGMENT,
    STUDY_CREATE,
    STUDY_UPDATE,
    STUDY_DELETE,
    PARTICIPANT_ENROLL,
    PARTICIPANT_WITHDRAW,
    PARTICIPANT_DATA_ACCESS,
    JOB_CREATED,
    DATA_DELETION,
}
