package dev.phoneagent.core.session

import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.openai.WireOptions
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * S1: every name that reaches disk through an enum value or a sealed discriminator, and every
 * event type with its ignorable flag, is listed in `session-format/format-names.txt`. A version's
 * fixture only shows the values the samples happen to use; this list locks the rest.
 *
 * A renamed or removed name needs a new format version with a migration; an added name is
 * listed in the same commit.
 */
@OptIn(ExperimentalSerializationApi::class)
class FormatNamesTest {
    private val inventory = File("src/test/resources/session-format/format-names.txt")

    /** What is persisted: the session log, and the provider settings the app stores. */
    private val roots =
        listOf(
            serializer<Envelope>(),
            serializer<SessionHeader>(),
            serializer<SessionEvent>(),
            serializer<ProviderProfile>(),
            serializer<ModelCapabilities>(),
            serializer<WireOptions>(),
        ).map { it.descriptor }

    @Test
    fun `the persisted names are exactly the committed inventory`() {
        val actual =
            names(roots) +
                SessionEventTypes.names.sorted().map { "event $it ignorable=${SessionEventTypes.isIgnorable(it)}" }
        val listed = inventory.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(emptyList<String>(), listed - actual.toSet(), "renamed or removed: needs a new format version")
        assertEquals(emptyList<String>(), actual - listed.toSet(), "new names: add them to ${inventory.name}")
        assertEquals(listed, actual, "order")
    }

    @Test
    fun `every serializable enum and sealed type of core is persisted or explicitly in memory only`() {
        val reachable = reachable(roots)
        val declared = serializableEnumsAndSealed().map { short(it.serialName) }.toSet()
        assertEquals(emptyList<String>(), (declared - reachable - IN_MEMORY_ONLY).sorted(), "not reachable from a root")
        assertEquals(emptyList<String>(), (IN_MEMORY_ONLY - declared).sorted(), "stale IN_MEMORY_ONLY entries")
        assertEquals(emptyList<String>(), (IN_MEMORY_ONLY intersect reachable).sorted(), "persisted after all")
    }

    @Test
    fun `the walker sees enum values and sealed names through lists, maps and nullable fields`() {
        val lines = names(listOf(serializer<WalkerSample>().descriptor))
        assertEquals(
            listOf(
                "enum session.StepOutcome: ${StepOutcome.entries.joinToString()}",
                "enum session.TurnEndReason: ${TurnEndReason.entries.joinToString()}",
                "sealed session.SurfaceOp: append, replace",
            ),
            lines,
        )
    }

    private fun names(roots: List<SerialDescriptor>): List<String> =
        walk(roots)
            .mapNotNull { descriptor ->
                val name = short(descriptor.serialName)
                if (descriptor.kind == SerialKind.ENUM) {
                    "enum $name: ${descriptor.elementNames.joinToString()}"
                } else {
                    sealedNames(descriptor)?.let { "sealed $name: ${it.joinToString()}" }
                }
            }.sorted()

    private fun sealedNames(descriptor: SerialDescriptor): Iterable<String>? =
        if (descriptor.kind == PolymorphicKind.SEALED) descriptor.getElementDescriptor(1).elementNames else null

    private fun reachable(roots: List<SerialDescriptor>): Set<String> =
        walk(roots)
            .filter { it.kind == SerialKind.ENUM || it.kind == PolymorphicKind.SEALED }
            .map { short(it.serialName) }
            .toSet()

    /**
     * Every named descriptor reachable from [roots], once each. Collections share one serial name
     * per kind, so they are walked every time; JSON trees are open and not walked.
     */
    private fun walk(roots: List<SerialDescriptor>): List<SerialDescriptor> {
        val seen = linkedMapOf<String, SerialDescriptor>()
        val pending = ArrayDeque(roots)
        while (pending.isNotEmpty()) {
            val descriptor = pending.removeFirst()
            if (isFirstVisit(descriptor, seen)) {
                pending +=
                    if (descriptor.kind == PolymorphicKind.SEALED) {
                        descriptor.getElementDescriptor(1).elementDescriptors
                    } else {
                        descriptor.elementDescriptors
                    }
            }
        }
        return seen.values.toList()
    }

    /** Whether [descriptor] is to be walked now; records named ones in [seen]. */
    private fun isFirstVisit(
        descriptor: SerialDescriptor,
        seen: MutableMap<String, SerialDescriptor>,
    ): Boolean {
        val name = descriptor.serialName.removeSuffix("?")
        val collection = descriptor.kind == StructureKind.LIST || descriptor.kind == StructureKind.MAP
        return when {
            name.startsWith("kotlinx.serialization.json.") -> false
            collection -> true
            else -> seen.put(name, descriptor) == null
        }
    }

    private fun short(serialName: String) = serialName.removeSuffix("?").removePrefix("dev.phoneagent.core.")

    /** Serializers of the `@Serializable` enums and sealed types compiled from core's main sources. */
    private fun serializableEnumsAndSealed(): List<SerialDescriptor> {
        val classes =
            Path.of(
                SessionEvent::class.java.protectionDomain.codeSource.location
                    .toURI(),
            )
        val loader = SessionEvent::class.java.classLoader
        return Files
            .walk(classes)
            .use { files ->
                files
                    .filter { it.toString().endsWith(".class") }
                    .map {
                        classes
                            .relativize(it)
                            .toString()
                            .removeSuffix(".class")
                            .replace(File.separatorChar, '.')
                    }.toList()
            }.map { Class.forName(it, false, loader) }
            .filter {
                it.isAnnotationPresent(Serializable::class.java) &&
                    (it.isEnum || it.isInterface || isAbstract(it))
            }.map { (serializer(it) as KSerializer<*>).descriptor }
            .filter { it.kind == SerialKind.ENUM || it.kind == PolymorphicKind.SEALED }
    }

    private fun isAbstract(type: Class<*>) =
        java.lang.reflect.Modifier
            .isAbstract(type.modifiers)

    private companion object {
        /**
         * Serializable contract types that no persisted format contains (port state, tool metadata,
         * UI cards). One that starts being persisted fails the last check above and moves to a root.
         */
        val IN_MEMORY_ONLY =
            setOf(
                "ports.SurfaceCapability",
                "ports.SurfaceUnavailableReason",
                "tool.RiskClass",
                "tool.StepState",
                "tool.ToolGroup",
            )
    }
}

@Serializable
private data class WalkerSample(
    val list: List<SurfaceOp>,
    val map: Map<String, TurnEndReason?>,
    val nullable: StepOutcome? = null,
)
