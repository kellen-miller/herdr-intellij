# Herdr for JetBrains

Native JetBrains integration for inspecting and controlling Herdr agents.

Herdr appears as an IntelliJ IDEA tool window for browsing workspaces and
agents, launching and prompting agents, responding when they block, opening
worktrees and files, and reviewing ordinary working-tree changes. IntelliJ owns
the human interface; the headless Herdr runtime and its agents keep running when
the IDE closes.

## Build

Use Java 21, then run:

```shell
./gradlew check buildPlugin
```

The installable ZIP is written to `build/distributions/`.
