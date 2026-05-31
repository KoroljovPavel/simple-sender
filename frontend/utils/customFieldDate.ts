// DATE custom fields bridge two formats. The UI uses a native <input type="date">, which only
// produces / consumes a date-only 'YYYY-MM-DD' string. The backend CustomFieldValueValidator parses
// DATE values with OffsetDateTime.parse(...), i.e. it requires a full ISO-8601 date-time (e.g.
// '2026-05-31T00:00:00Z') and rejects a bare 'YYYY-MM-DD' with custom_field_type_mismatch (422).

const DATE_ONLY_RE = /^\d{4}-\d{2}-\d{2}$/

// 'YYYY-MM-DD' (date input) -> 'YYYY-MM-DDT00:00:00Z' (backend OffsetDateTime).
// Pass-through when the value already carries a time component; empty/null -> null.
export function dateInputToIso(value: unknown): string | null {
  if (value === null || value === undefined) return null
  const s = String(value).trim()
  if (s === '') return null
  if (DATE_ONLY_RE.test(s)) return `${s}T00:00:00Z`
  return s // already a date-time (or unexpected) — let the backend validate it
}

// Stored ISO-8601 date-time (or date) -> 'YYYY-MM-DD' for the date input. We slice the literal date
// prefix instead of going through Date() so a stored UTC midnight never shifts a day in a non-UTC
// browser. Empty/unparseable -> ''.
export function isoToDateInput(value: unknown): string {
  if (value === null || value === undefined) return ''
  const m = String(value).match(/^\d{4}-\d{2}-\d{2}/)
  return m ? m[0] : ''
}
