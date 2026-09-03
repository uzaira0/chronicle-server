package com.openlattice.chronicle.services.download

import com.openlattice.chronicle.constants.EdmConstants.DATE_LOGGED_FQN
import com.openlattice.chronicle.constants.EdmConstants.DATE_TIME_FQN
import com.openlattice.chronicle.constants.EdmConstants.DURATION_FQN
import com.openlattice.chronicle.constants.EdmConstants.FULL_NAME_FQN
import com.openlattice.chronicle.constants.EdmConstants.GENERAL_END_TIME_FQN
import com.openlattice.chronicle.constants.EdmConstants.NEW_APP_FQN
import com.openlattice.chronicle.constants.EdmConstants.NEW_PERIOD_FQN
import com.openlattice.chronicle.constants.EdmConstants.RECORD_TYPE_FQN
import com.openlattice.chronicle.constants.EdmConstants.START_DATE_TIME_FQN
import com.openlattice.chronicle.constants.EdmConstants.TIMEZONE_FQN
import com.openlattice.chronicle.constants.EdmConstants.TITLE_FQN
import com.openlattice.chronicle.constants.EdmConstants.USER_FQN
import com.openlattice.chronicle.constants.EdmConstants.WARNING_FQN
import com.openlattice.chronicle.constants.ParticipantDataType

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 */
public class DownloadTypePropertyTypeFqns private constructor() {
    internal companion object {
        public val SRC = mapOf(
                ParticipantDataType.USAGE_DATA to linkedSetOf(TITLE_FQN, FULL_NAME_FQN),
                ParticipantDataType.RAW_DATA to linkedSetOf(
                        DATE_LOGGED_FQN,
                        TIMEZONE_FQN,
                        TITLE_FQN,
                        FULL_NAME_FQN,
                        RECORD_TYPE_FQN
                ),
                ParticipantDataType.PREPROCESSED to linkedSetOf(
                        NEW_APP_FQN,
                        TIMEZONE_FQN,
                        START_DATE_TIME_FQN,
                        GENERAL_END_TIME_FQN,
                        RECORD_TYPE_FQN,
                        TITLE_FQN,
                        FULL_NAME_FQN,
                        NEW_PERIOD_FQN,
                        DURATION_FQN,
                        WARNING_FQN
                )
        )

        public val EDGE = mapOf(
                ParticipantDataType.USAGE_DATA to linkedSetOf(USER_FQN, DATE_TIME_FQN),
                ParticipantDataType.RAW_DATA to linkedSetOf(),
                ParticipantDataType.PREPROCESSED to linkedSetOf()
        )
    }
}
