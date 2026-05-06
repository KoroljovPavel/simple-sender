export type UserStatus = 'pending' | 'active' | 'blocked' | 'deleted'

export interface User {
  id: string
  email: string
  name: string
  status: UserStatus
}
