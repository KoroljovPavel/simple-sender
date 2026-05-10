import { defineStore } from 'pinia'
import type { Project } from '~/types/project'

export const LOCAL_STORAGE_KEY = 'bot-funnel.currentProjectId'

const readPersisted = (): string | null => {
  if (!import.meta.client) return null
  try {
    return localStorage.getItem(LOCAL_STORAGE_KEY)
  } catch {
    return null
  }
}

const writePersisted = (id: string | null) => {
  if (!import.meta.client) return
  try {
    if (id) localStorage.setItem(LOCAL_STORAGE_KEY, id)
    else localStorage.removeItem(LOCAL_STORAGE_KEY)
  } catch {
    // QuotaExceededError or storage disabled — in-memory state still correct.
  }
}

const sortByCreatedAtDesc = (list: Project[]): Project[] =>
  [...list].sort((a, b) => (a.createdAt < b.createdAt ? 1 : a.createdAt > b.createdAt ? -1 : 0))

export const useProjectsStore = defineStore('projects', () => {
  const projects = ref<Project[]>([])
  const currentProjectId = ref<string | null>(readPersisted())
  const isLoaded = ref(false)
  let inFlight: Promise<void> | null = null

  const currentProject = computed<Project | null>(
    () => projects.value.find((p) => p.id === currentProjectId.value) ?? null,
  )

  function selectProject(id: string | null) {
    currentProjectId.value = id
    writePersisted(id)
  }

  function runAutoSelect() {
    const active = sortByCreatedAtDesc(projects.value.filter((p) => p.deletedAt === null))
    const persisted = currentProjectId.value
    const keep = persisted !== null && active.some((p) => p.id === persisted)
    selectProject(keep ? persisted : (active[0]?.id ?? null))
  }

  async function fetchAll(includeDeleted = false): Promise<void> {
    if (inFlight) return inFlight
    const run = (async () => {
      try {
        const data = await useApi()<Project[]>('/api/v1/projects', {
          query: includeDeleted ? { include_deleted: true } : undefined,
        })
        projects.value = data
        runAutoSelect()
      } finally {
        isLoaded.value = true
        inFlight = null
      }
    })()
    inFlight = run
    return run
  }

  async function create(payload: { name: string; description: string | null; timezone: string }): Promise<Project> {
    const created = await useApi()<Project>('/api/v1/projects', { method: 'POST', body: payload })
    projects.value = [created, ...projects.value]
    selectProject(created.id)
    return created
  }

  async function update(
    id: string,
    partial: Partial<{ name: string; description: string | null; timezone: string }>,
  ): Promise<Project> {
    const updated = await useApi()<Project>(`/api/v1/projects/${id}`, { method: 'PATCH', body: partial })
    projects.value = projects.value.map((p) => (p.id === id ? updated : p))
    return updated
  }

  async function softDelete(id: string): Promise<void> {
    await useApi()(`/api/v1/projects/${id}`, { method: 'DELETE' })
    projects.value = projects.value.filter((p) => p.id !== id)
    if (currentProjectId.value === id) runAutoSelect()
  }

  async function restore(id: string): Promise<Project> {
    const restored = await useApi()<Project>(`/api/v1/projects/${id}/restore`, { method: 'POST' })
    const exists = projects.value.some((p) => p.id === restored.id)
    projects.value = exists
      ? projects.value.map((p) => (p.id === restored.id ? restored : p))
      : [restored, ...projects.value]
    return restored
  }

  async function handleStaleCurrent(): Promise<void> {
    selectProject(null)
    await fetchAll()
  }

  return {
    projects,
    currentProjectId,
    isLoaded,
    currentProject,
    fetchAll,
    create,
    update,
    softDelete,
    restore,
    selectProject,
    handleStaleCurrent,
  }
})
