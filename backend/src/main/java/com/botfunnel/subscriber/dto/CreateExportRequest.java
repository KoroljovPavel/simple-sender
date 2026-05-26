package com.botfunnel.subscriber.dto;

import com.botfunnel.subscriber.SegmentFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body of {@code POST /subscribers/export} (Decision 7). Carries the segment to export; a null
 * {@code filter} exports the whole project (empty segment). The {@code filter}'s {@code cursor} /
 * {@code limit} fields are ignored by the export pipeline — an export always spans the full segment.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateExportRequest(SegmentFilter filter) {
}
