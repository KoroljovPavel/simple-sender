## Language
- Communicate with user, plans, plan-mode: Ukrainian
- Tech docs (code, context, rules, CLAUDE.md, skills): English
- AI prompts: English
- User-facing docs (README): Ukrainian

## Behavior

- No "Great question!", no filler, no water.
- NEVER use AskUserQuestion tool. Ask questions as plain text in chat instead.
- ALL deployments via GitHub CI/CD only. Direct server access (SSH, container restarts) only for emergency debugging of broken production.

## Task Planning
- Use TodoWrite for multi-step tasks (>1 step)
- When user asks for team/swarm of agents: use TeamCreate, not TaskCreate

## Security

- NEVER ask user to write secrets in chat
- Instead: provide instructions where to store them securely
  - Local: `.env` files, config files
  - CI/CD: GitHub Actions secrets
- ALWAYS ask before Deploy/push to main/production
- ALWAYS add secrets to `.gitignore`: `.env`, `*.key`, `credentials.json`, `secrets/`
- Be cautious with external actions (push, deploy, send messages, create PRs). Ask before acting externally when uncertain.

## Git

- NEVER use `git add -f` / `--force` to bypass `.gitignore`. If `git add` reports a path is ignored, that is the user's intent — skip those files and commit only what is actually tracked. If nothing remains to commit, skip the commit entirely instead of forcing.
- NEVER use `git commit --no-verify` or skip pre-commit hooks unless the user explicitly asks.
- The `work/` directory is gitignored on purpose. Skills/commands that say "git commit" for spec/task/review/decisions artifacts inside `work/` (e.g. `feature-execution`, `task-decomposition`, `user-spec-planning`, `tech-spec-planning`, `methodology`, `do-task`) must treat those commits as no-ops when the path is ignored — do not force-add.
