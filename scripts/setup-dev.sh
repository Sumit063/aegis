#!/usr/bin/env bash
# One-time setup for a fresh clone: installs git hooks and checks the toolchain.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

git config core.hooksPath .githooks
chmod +x .githooks/* scripts/*.sh mvnw
echo "✓ git hooks installed (.githooks): pre-commit, commit-msg, pre-push"

if java -version 2>&1 | grep -Eq 'version "(2[5-9]|[3-9][0-9])'; then
  echo "✓ $(java -version 2>&1 | head -n1)"
else
  echo "✗ JDK 25+ not found on PATH. Install it (e.g. brew install openjdk@25) and set JAVA_HOME." >&2
fi

if docker info >/dev/null 2>&1; then
  echo "✓ Docker is reachable (needed for integration tests)"
else
  echo "✗ Docker is not reachable. Start Docker Desktop or 'colima start'." >&2
fi
