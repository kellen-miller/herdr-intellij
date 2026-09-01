---
status: accepted
---

# Herdr authorizes launch kinds

Treat a versioned Herdr runtime capability response as the sole authority for agent launch kinds shown by the plugin. A plugin-maintained list would drift from the kinds accepted by `agent.start`, while executable detection or provider defaults would leak runtime policy into the interface; the response therefore contains only stable canonical `kind` and human `label` values.

## Consequences

Herdr must add `server.agent_capabilities` and bump its protocol version before this plugin can ship. The plugin requires exact protocol equality and disables actions on mismatch rather than guessing or keeping a compatibility list.
