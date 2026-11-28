package dev.phoneagent.core.testing

import dev.phoneagent.core.model.CredentialKind
import dev.phoneagent.core.model.WireProfile

/** A minimal wire snapshot for tests that do not care about wire settings. */
val TEST_WIRE: WireProfile = WireProfile(mappingVersion = 1, credential = CredentialKind.NONE)
