import type { APIRequestContext } from '@playwright/test'

// Single home for the synthetic-webhook POST so it is not scattered across the spec (Task 11
// implementation hint). Simulates a real Telegram "/start" private-chat update against the backend
// webhook endpoint, which (for a CONNECTED bot whose stored secret matches `secret`) upserts a
// Subscriber via ProcessTelegramUpdateJob → subscriberService.upsertFromTelegramUpdate.

export interface StartFixture {
  telegramUserId: number
  chatId: number
  firstName: string
  updateId?: number
}

export async function seedStartUpdate(
  request: APIRequestContext,
  backendUrl: string,
  projectId: string,
  secret: string,
  fx: StartFixture,
): Promise<void> {
  const updateId = fx.updateId ?? Date.now()
  const body = {
    update_id: updateId,
    message: {
      message_id: updateId,
      date: Math.floor(Date.now() / 1000),
      text: '/start',
      chat: { id: fx.chatId, type: 'private', first_name: fx.firstName },
      from: { id: fx.telegramUserId, is_bot: false, first_name: fx.firstName },
    },
  }
  const resp = await request.post(`${backendUrl}/webhooks/telegram/${projectId}`, {
    headers: { 'X-Telegram-Bot-Api-Secret-Token': secret, 'Content-Type': 'application/json' },
    data: body,
  })
  if (!resp.ok()) {
    throw new Error(`webhook seed failed for tg_user=${fx.telegramUserId}: ${resp.status()} ${await resp.text()}`)
  }
}
