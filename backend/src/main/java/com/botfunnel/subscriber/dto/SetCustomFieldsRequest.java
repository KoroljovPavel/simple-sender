package com.botfunnel.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

// PATCH body for subscriber custom-field values: {"values": {"<name>": <value>, ...}}. The
// mass-assignment defense (AC11) is applied per-key inside the controller (keys not present in the
// project's customFieldDefinitions are silently dropped) — @JsonIgnoreProperties here only guards
// the top-level envelope against unknown sibling fields.
@JsonIgnoreProperties(ignoreUnknown = true)
public record SetCustomFieldsRequest(
        Map<String, Object> values
) {}
