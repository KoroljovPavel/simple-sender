import { describe, it, expect } from 'vitest'
import { dateInputToIso, isoToDateInput } from '../../utils/customFieldDate'

describe('customFieldDate', () => {
  describe('dateInputToIso', () => {
    it('converts a date-only string to ISO-8601 UTC midnight', () => {
      expect(dateInputToIso('2026-05-31')).toBe('2026-05-31T00:00:00Z')
    })
    it('passes through a value that already has a time component', () => {
      expect(dateInputToIso('2026-05-31T12:30:00Z')).toBe('2026-05-31T12:30:00Z')
    })
    it('trims surrounding whitespace before converting', () => {
      expect(dateInputToIso('  2026-05-31  ')).toBe('2026-05-31T00:00:00Z')
    })
    it.each([null, undefined, '', '   '])('maps empty/blank %s to null', (v) => {
      expect(dateInputToIso(v)).toBeNull()
    })
  })

  describe('isoToDateInput', () => {
    it('extracts the date prefix from an ISO-8601 date-time', () => {
      expect(isoToDateInput('2026-05-31T00:00:00Z')).toBe('2026-05-31')
    })
    it('does not shift the day for a UTC-midnight value (literal slice, not Date())', () => {
      expect(isoToDateInput('2026-01-01T00:00:00Z')).toBe('2026-01-01')
    })
    it('returns a bare date unchanged', () => {
      expect(isoToDateInput('2026-05-31')).toBe('2026-05-31')
    })
    it.each([null, undefined, '', 'not-a-date'])('maps empty/unparseable %s to ""', (v) => {
      expect(isoToDateInput(v)).toBe('')
    })
  })

  it('round-trips date input -> wire -> date input', () => {
    expect(isoToDateInput(dateInputToIso('2026-05-31'))).toBe('2026-05-31')
  })
})
