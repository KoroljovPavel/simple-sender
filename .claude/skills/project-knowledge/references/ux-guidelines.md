# UX Guidelines

## Principles

- **Functionality over aesthetics** on MVP. Ship a working product, polish later.
- Ready-made components only: shadcn-vue + Tailwind — no custom design system.
- **All UI text via `t('key')`.** Two locales: `uk` (default, no URL prefix) + `en` (`/en/...`). Every visible string lives in `frontend/i18n/locales/{uk,en}.json` — see `patterns.md` "Frontend i18n" for the full pattern set (parity gate, locale-aware navigation, reactive Zod, useApiError). Brand "Bot Funnel" is keyed but not localized (same value in both files).
- One theme on launch (no dark/light toggle).

## Responsiveness

- Main pages (subscribers, broadcasts, dashboard): mobile-friendly down to 768px.
- Funnel editor: desktop-first (≥ 1024px). Complex step tree is unusable on mobile.
- Admin panel: desktop-only is acceptable.

## Layout

### Authenticated User
- **Topbar:** logo | project selector (dropdown) | user avatar → Profile / Logout | LangSwitcher (UK | EN, right of Logout in the same flex-row)
- **Sidebar:** Dashboard · All projects · **Subscribers group** (Subscribers / Tags / Custom fields — Epic 09; the group + children render only when a project is selected, indented under the parent) · Funnels · Broadcasts · Settings (project-scoped Settings link visible only when a project is selected; "All projects" routes to `/projects` and is the only entry point to the Restore flow for soft-deleted projects). The group derives its link bases from the route's `projectId`, so a deep-link refresh keeps it populated (see `patterns.md` → Subscribers CRM).
- **Main area:** breadcrumbs + page content

### Auth (`/auth/*`) layout
- Centered card on a plain background; LangSwitcher absolute top-right (`top-4 right-4 z-10`) over the card.

### Admin Panel (`/admin`)
- Separate layout with admin-specific sidebar
- Accessible only to `isSuperAdmin = true` users; ordinary 403 response for everyone else (no hint the route exists)

## Key Interactions

### Empty States
- Zero projects → inline empty-state on /dashboard with "Create your first project" CTA
- Empty subscribers list → explain how subscribers appear (via bot interaction)
- Empty funnels list → CTA to create first funnel

### Destructive Actions
- Project deletion: requires typing the project name to confirm
- Account deletion: confirmation modal listing consequences (projects, bots, subscribers to be deleted) — no password required. Active session is sufficient proof of identity. (Overrides the earlier guideline that required password re-entry; rationale is Decision 5 in the 02-auth tech-spec.)
- Broadcast cancel in progress: confirmation dialog

### Project Selector
- Dropdown in topbar shows active projects (soft-deleted filtered out) sorted by `createdAt desc`
- Last selected project persisted in `localStorage` (cross-restart only — see `patterns.md` "SSR-snapshot stomp recovery" for the Pinia gotcha)
- Selecting a project updates Pinia in-memory state; **no page reload** — components reactive to `currentProject` re-render. **No cross-tab sync** via `storage` event — each tab keeps its own current project (intentional for multi-brand operators editing two projects in parallel)
- At the 5-active-project cap, "+ Create new project" renders disabled with an always-visible explanatory text linked via `aria-describedby` (native `title` attr is invisible on disabled buttons and on mobile — don't reuse that pattern)
- When the current project becomes stale (soft-deleted in another tab, hard-deleted by cron), the next API hit to `/api/v1/projects/{currentProjectId}` returns 404 → interceptor clears the selection, refetches the list, auto-selects the next active project, and surfaces a persistent dismissable banner on `/projects` via `projectsStore.pendingBannerKey`

## Error Handling

- Form validation: inline errors below fields, not toast
- API errors: toast notification with human-readable message. Toasts use `vue-sonner` (`<Toaster rich-colors position="bottom-right">` in `layouts/default.vue`) — success = green, error = red. `vue-sonner/style.css` MUST be imported in the layout or the toaster mispositions (no container CSS).
- Inline-editable fields with a per-field Save (e.g. subscriber custom fields): show an "unsaved" cue and disable Save until the field actually diverges from server truth, so the operator never loses track of what's persisted (dirty-tracking pattern in `patterns.md` → Subscribers CRM).
- Network errors: retry button where applicable
- 403 / 404 pages: minimal, no technical details exposed

## Funnel Editor

- Vertical step list (not canvas/drag-drop on MVP)
- Each step: inline edit form for simple types (Send Message)
- Modal form for complex types (Wait for Reply with branches)
- Step preview panel on the right showing how message looks in Telegram
- Buttons: `+ Add step` · `↑ Move up` · `↓ Move down` · `✕ Delete`

## Analytics Charts

- Bar chart: new subscribers by day (30 days)
- Line chart: messages sent by day, two series (funnel vs broadcast)
- Funnel drop-off: horizontal progress bar per step
- Button split: horizontal bar chart per branch
- Recharts library (vue-chartjs wrapper for Nuxt)
