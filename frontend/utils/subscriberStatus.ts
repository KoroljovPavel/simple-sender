import type { SubscriberStatus } from '~/types/subscriber'

// Status → badge color (Active=green, Unsubscribed=gray, Blocked/Deleted=red). Kept as a single const
// (not inline in templates) so it is unit-testable and shared by SubscribersTable + SubscriberProfileCard.
// shadcn Badge has no green/amber variant, so we drive color with explicit border/bg/text classes on the
// neutral `outline` variant.
export const STATUS_BADGE_CLASS: Record<SubscriberStatus, string> = {
  active: 'border-green-300 bg-green-50 text-green-700',
  unsubscribed: 'border-gray-300 bg-gray-100 text-gray-600',
  blocked: 'border-red-300 bg-red-50 text-red-700',
  deleted: 'border-red-300 bg-red-50 text-red-700',
}

export function statusBadgeClass(status: SubscriberStatus): string {
  return STATUS_BADGE_CLASS[status] ?? STATUS_BADGE_CLASS.unsubscribed
}
