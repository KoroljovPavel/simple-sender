import { describe, it, expect } from 'vitest'
import { statusBadgeClass, STATUS_BADGE_CLASS } from '../../utils/subscriberStatus'

describe('statusBadgeClass', () => {
  it('maps active→green, unsubscribed→gray, blocked/deleted→red', () => {
    expect(statusBadgeClass('active')).toContain('green')
    expect(statusBadgeClass('unsubscribed')).toContain('gray')
    expect(statusBadgeClass('blocked')).toContain('red')
    expect(statusBadgeClass('deleted')).toContain('red')
  })

  it('returns a defined class for every known status', () => {
    for (const status of Object.keys(STATUS_BADGE_CLASS) as (keyof typeof STATUS_BADGE_CLASS)[]) {
      expect(statusBadgeClass(status)).toBe(STATUS_BADGE_CLASS[status])
    }
  })

  it('falls back to the unsubscribed (neutral) class for an unknown status', () => {
    // @ts-expect-error — exercising the runtime fallback branch with an off-spec value
    expect(statusBadgeClass('weird')).toBe(STATUS_BADGE_CLASS.unsubscribed)
  })
})
