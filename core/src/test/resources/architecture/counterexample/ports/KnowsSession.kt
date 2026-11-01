package dev.phoneagent.core.ports

import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.session.SessionStore

// Rejected: ports (layer 1) reaches up to session (layer 4).
class KnowsSession(
    val task: TaskId,
    val store: SessionStore,
)
