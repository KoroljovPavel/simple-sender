package com.botfunnel.subscriber.export;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for the signed export-download URL shape, shared by the controller (list /
 * refresh-url responses) and the JobRunr worker (export-ready email) so the format cannot drift
 * between the two. The token is base64url (no padding) and therefore URL-safe — no encoding needed.
 */
@Component
public class ExportUrlBuilder {

    private final String appUrl;

    public ExportUrlBuilder(@Value("${app.url}") String appUrl) {
        this.appUrl = appUrl;
    }

    public String downloadUrl(String projectId, String exportId, String token) {
        return appUrl + "/api/v1/projects/" + projectId
                + "/subscribers/exports/" + exportId + "/download?token=" + token;
    }
}
