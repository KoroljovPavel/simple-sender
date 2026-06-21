# Project: Bot Funnel Service

## Overview

Bot Funnel Service is a multi-channel messenger funnel platform. Users register, create projects, connect their Telegram bots, and build automated communication sequences with subscribers. Supports mass broadcasts and a public REST API for external integrations.

**MVP goal:** A user connects their bot, builds a linear funnel with 3–5 steps, and sends the first broadcast to 100+ subscribers — all within 10 minutes of signing up.

**MVP channel:** Telegram only. Other channels (Viber, Instagram, WhatsApp) are deferred post-MVP.

## Target Audience

- Small business owners and marketers who use Telegram bots for customer communication
- Developers who need a programmable funnel/broadcast platform via REST API
- Platform owner (Super Admin): 1–2 people (founder + tech lead)

## Core Problem

Building automated Telegram communication flows requires either custom bot development or expensive no-code tools. Bot Funnel Service provides a self-hosted, API-first platform that lets non-technical users create funnels visually while giving developers full programmatic control.

## Roles

| Role | Description |
|------|-------------|
| **Super Admin** | Platform owner. Created via seed script. Full read access, can block/delete users and projects. Cannot see raw bot tokens. |
| **User** | Self-registered via email + password. Owns one or more projects. Sees only their own data. |
| **Subscriber** | Telegram end-user interacting with a bot. Not a service account — stored per-project. Same Telegram user in two projects = two separate Subscriber records (intentional isolation). |

## Key Features (MVP)

| # | Feature | Priority | Epic |
|---|---------|----------|------|
| 1 | User registration, login, email verification, password reset | Critical | 02 |
| 2 | Projects (isolated workspaces, up to 5 per user) | Critical | 03 |
| 3 | Telegram bot connection via token, webhook setup | Critical | 04 |
| 4 | Subscriber auto-registration, tags, custom fields, segments | Critical | 05 |
| 5 | Funnel builder (linear steps: message, delay, wait-for-reply, branch, tag actions) | Critical | 06 |
| 6 | Mass broadcasts with audience segmentation and scheduling | Critical | 07 |
| 7 | Public REST API + outgoing webhooks | Important | 08 |
| 8 | Project dashboard analytics (subscribers, messages, funnel stats) | Important | 09 |
| 9 | Super Admin panel (users, projects, audit log, queue status) | Important | 10 |
| 10 | Web UI — user cabinet | Critical | 11 |

## Entity Hierarchy

```
User
 └── Project (1..N, max 5)
      ├── Bot (1 per project on MVP)
      ├── Subscriber (N) — auto-created from Telegram interactions
      ├── Funnel (N)
      ├── Broadcast (N)
      ├── Custom Field definitions (N, max 20)
      └── API Key (1 primary)
```

## Out of Scope (MVP)

- Multiple channels per project (Viber, Instagram, WhatsApp, Email, SMS)
- Team collaboration (multiple owners per project)
- Billing and subscription plans
- Drag-and-drop visual funnel editor (canvas)
- A/B testing for funnels and broadcasts
- OAuth login (Google, GitHub, Telegram Widget)
- 2FA / TOTP
- Kubernetes / multi-region deployment
- AI steps in funnels
- Click tracking, cohort analysis, custom dashboards

## Post-Launch Backlog (Phase 2+)

- Additional channels: Viber, Instagram Direct, WhatsApp Business, Facebook Messenger
- Team roles inside projects (Owner / Editor / Viewer)
- Complex segment builder (AND/OR/NOT, custom field conditions)
- Drag-and-drop funnel canvas editor
- Recurring broadcasts and A/B tests
- Billing: Free / Starter / Pro plans (Stripe)
- GraphQL endpoint, WebSocket real-time updates
- OAuth apps for third-party integrations
- AI step in funnels (OpenAI / Anthropic)
- GDPR tooling (data export, right to deletion)
