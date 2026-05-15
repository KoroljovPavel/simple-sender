export type BotStatus = 'connected' | 'disconnected'

export interface Bot {
  telegramBotId: number
  telegramUsername: string
  telegramFirstName: string
  tokenSuffix: string
  status: BotStatus
  connectedAt: string
}
