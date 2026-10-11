package com.openlattice.chronicle.util;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import com.openlattice.chronicle.data.FileType;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;

import static com.openlattice.chronicle.constants.EdmConstants.PARTICIPANTS_PREFIX;
import com.openlattice.chronicle.constants.CustomMediaType;

/**
 * @author alfoncenzioka &lt;alfonce@openlattice.com&gt;
 */
public final class ChronicleServerUtil {

    private ChronicleServerUtil() {}

    public static String ORG_STUDY_PARTICIPANT            = " - orgId = {}, studyId = {}, participantId = {}";
    public static String ORG_STUDY_PARTICIPANT_DATASOURCE = " - orgId = {}, studyId = {}, participantId = {}, dataSourceId = {}";
    public static final String STUDY_PARTICIPANT_DATASOURCE = " - studyId = {}, participantId = {}, dataSourceId = {}";
    public static final String STUDY_PARTICIPANT = " - studyId = {}, participantId = {}";
    public static final String STUDY_PARTICIPANTS = " - studyId = {}, participantIds = {}";

    @Deprecated( since = "apps v2" )
    public static String getParticipantEntitySetName( UUID studyId ) {
        return PARTICIPANTS_PREFIX.concat( studyId.toString() );
    }

    public static void setDownloadContentType( HttpServletResponse response, FileType fileType ) {

        if ( fileType == null ) {
            response.setContentType( MediaType.APPLICATION_JSON_VALUE );
            return;
        }

        switch ( fileType ) {
            case csv:
                response.setContentType( CustomMediaType.TEXT_CSV_VALUE );
                break;
            case yaml:
                response.setContentType( CustomMediaType.TEXT_YAML_VALUE );
                break;
            case json:
            default:
                response.setContentType( MediaType.APPLICATION_JSON_VALUE );
                break;
        }
    }

    public static void setContentDisposition( HttpServletResponse response, String fileName, FileType fileType ) {

        if ( fileType == FileType.csv || fileType == FileType.json ) {
            // Sanitize fileName to prevent CRLF header injection (CWE-113)
            String safeName = fileName.replaceAll("[\\r\\n\"\\\\/]", "_");
            response.setHeader(
                    "Content-Disposition",
                    "attachment; filename=\"" + safeName + "." + fileType.toString() + "\""
            );
        }
    }

    // authentication token

    public static String getTokenFromContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return ( (JwtAuthenticationToken) authentication ).getToken().getTokenValue();
    }
}
