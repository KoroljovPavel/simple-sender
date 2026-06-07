package com.botfunnel.funnel.dto;

// Stop-all response: the best-effort number of in-flight executions transitioned to cancelled by the
// bulk-cancel sweep (Decision 8 — modifiedCount; a run that started/finished alongside the engine scan
// may not be counted, which is an accepted race). 0 is a valid result (no active runs), not an error.
public record StopAllResponse(long cancelled) {}
