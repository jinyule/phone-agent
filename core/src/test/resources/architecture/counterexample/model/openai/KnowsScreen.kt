@file:Suppress("ktlint:standard:no-wildcard-imports")

package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.screen.*

// Rejected: one capability (model.openai) uses another (screen) through a wildcard import.
abstract class KnowsScreen : ChatModel
