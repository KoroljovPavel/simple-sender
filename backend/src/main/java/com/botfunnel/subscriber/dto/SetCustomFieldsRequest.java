package com.botfunnel.subscriber.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;

import java.util.Map;

// PATCH body for subscriber custom-field values: {"values": {"<name>": <value>, ...}}. The
// mass-assignment defense (AC11) is applied per-key inside the controller (keys not present in the
// project's customFieldDefinitions are silently dropped) — @JsonIgnoreProperties here only guards
// the top-level envelope against unknown sibling fields. @Size caps the entry count well above the
// 20-definition ceiling so a hostile client can't ship an unbounded map (low-grade DoS surface).
@JsonIgnoreProperties(ignoreUnknown = true)
public record SetCustomFieldsRequest(
        @Size(max = 100) Map<String, Object> values
) {}
