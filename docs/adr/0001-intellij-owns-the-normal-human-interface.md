---
status: accepted
---

# IntelliJ owns the normal human interface

Use an IntelliJ tool window as the normal human interface for the local Herdr runtime while Herdr remains the persistent, headless owner of workspaces and agents. A separate Herdr terminal or TUI would duplicate interaction state and force users to switch applications; attaching runtime lifetime to IntelliJ would instead stop valuable work on IDE exit.

## Consequences

Closing or uninstalling the plugin only disconnects the interface. Herdr, agents, worktrees, and open project state continue until managed through Herdr outside this MVP; terminal emulation and raw pane control are intentionally absent.
