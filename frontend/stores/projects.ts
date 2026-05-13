import { defineStore } from 'pinia'
import type { Project } from '~/types/project'

export const LOCAL_STORAGE_KEY = 'bot-funnel.currentProjectId'

// Indirection over `import.meta.client` so the SSR-guard branch (Risk R6) can be
// exercised under the Nuxt vitest environment, which fixes `import.meta.client = true`
// at compile time. Production path is unchanged: the default reads `import.meta.client`.
let isClientGuard: () => boolean = () => import.meta.client
export const __setClientGuardForTests = (fn: () => boolean) => {
  isClientGuard = fn
}

const readPersisted = (): string | null => {
  if (!isClientGuard()) return null
  try {
    return localStorage.getItem(LOCAL_STORAGE_KEY)
  } catch {
    return null
  }
}

const writePersisted = (id: string | null) => {
  if (!isClientGuard()) return
  try {
    if (id) localStorage.setItem(LOCAL_STORAGE_KEY, id)
    else localStorage.removeItem(LOCAL_STORAGE_KEY)
  } catch (err) {
    // QuotaExceededError or storage disabled — in-memory state stays correct.
    console.warn('[projects] localStorage write failed; in-memory state preserved', err)
  }
}

const sortByCreatedAtDesc = (list: Project[]): Project[] =>
  [...list].sort((a, b) => (a.createdAt < b.createdAt ? 1 : a.createdAt > b.createdAt ? -1 : 0))

export const useProjectsStore = defineStore('projects', () => {
  const projects = ref<Project[]>([])
  const currentProjectId = ref<string | null>(readPersisted())
  const isLoaded = ref(false)
  // Status surface for cross-cutting layers (e.g. useApi 404 interceptor) that
  // cannot reach into a page-local ref. The layout/host renders an inline
  // banner bound to this i18n key and clears it on dismiss/route change.
  // See work/05-projects/decisions.md Task 5 for the rationale.
  const pendingBannerKey = ref<string | null>(null)
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
    // SSR-snapshot stomp recovery: the ref(readPersisted()) initializer runs
    // server-side too, where readPersisted() returns null. Pinia's hydration
    // then replays that null onto the client ref, overriding the client-side
    // localStorage read. By the time fetchAll lands here (client-only via the
    // dashboard onMounted etc.), we must re-read localStorage if the ref is
    // still null — otherwise we lose the user's cross-restart selection and
    // overwrite it with active[0].
    // Explicit-clear paths (handleStaleCurrent → selectProject(null) → writePersisted(null))
    // already remove the localStorage key, so a re-read returns null and the
    // fallback to active[0] still kicks in correctly.
    let persisted = currentProjectId.value
    if (persisted === null) persisted = readPersisted()
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
    await useApi()<void>(`/api/v1/projects/${id}`, { method: 'DELETE' })
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
    pendingBannerKey,
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
