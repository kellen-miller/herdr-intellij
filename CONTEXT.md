# Herdr IntelliJ

This context names the concepts exposed by the IntelliJ command center for a local Herdr runtime.

## Language

**Herdr runtime**:
The persistent, headless Herdr process that owns workspaces, agents, and their continued execution after IntelliJ disconnects.
_Avoid_: Backend, daemon, IntelliJ server

**Connection**:
One IntelliJ application's live view of the local Herdr runtime's default session.
_Avoid_: Herdr session, client session

**Workspace**:
A Herdr-owned coding context rooted at a directory and containing zero or more agents.
_Avoid_: IntelliJ project, repository, folder

**Workspace root**:
The directory against which a workspace's file paths and current working-tree changes are interpreted.
_Avoid_: Project root, repository root, agent directory

**Agent**:
A Herdr-recognized coding-agent process associated with one allocated pane.
_Avoid_: Bot, provider, terminal

**Launch kind**:
A canonical Herdr identifier and human label for a kind of agent that Herdr can start.
_Avoid_: Provider, executable, agent type

**Provisioning record**:
A temporary command-center entry for a newly allocated agent location whose launch outcome is not yet known.
_Avoid_: Placeholder agent, pending terminal

**Failed launch**:
A retained allocated agent location whose launch failed or had an ambiguous outcome, together with evidence needed to refresh or retry safely.
_Avoid_: Dead agent, discarded tab

**Current working-tree changes**:
The IntelliJ VCS view of uncommitted changes under a workspace root, without claiming which agent produced them.
_Avoid_: Agent changes, agent diff
