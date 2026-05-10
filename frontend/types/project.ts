export interface Project {
  id: string
  name: string
  description: string | null
  timezone: string
  createdAt: string
  updatedAt: string
  deletedAt: string | null
}
