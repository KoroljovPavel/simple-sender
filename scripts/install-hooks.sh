#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
HOOK_PATH="$REPO_ROOT/.git/hooks/pre-commit"

cat > "$HOOK_PATH" << 'EOF'
#!/bin/sh
if ! command -v gitleaks >/dev/null 2>&1; then
  echo "WARNING: gitleaks not installed. Skipping secret scan."
  echo "Install: brew install gitleaks (macOS) or see docs/local-setup.md"
  exit 0
fi

gitleaks detect --staged --verbose --no-banner
EOF

chmod +x "$HOOK_PATH"
echo "pre-commit hook installed at $HOOK_PATH"
