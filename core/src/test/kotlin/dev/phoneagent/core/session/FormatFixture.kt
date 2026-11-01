package dev.phoneagent.core.session

import java.nio.file.Files
import java.nio.file.Path

/**
 * The committed format fixture `session-format/v{N}/session.jsonl`: the header and every
 * [SessionSamples] record, encoded by the codec of format version N.
 *
 * It is written once per format version, by `./gradlew :core:generateSessionFormatFixture`
 * (which runs [main]); a published version directory is never rewritten, so a format change
 * needs a new version.
 */
object FormatFixture {
    /** The fixture text [codec] writes for the samples. */
    fun text(codec: SessionCodec = SessionCodec()): String =
        (listOf(codec.encodeHeader(SessionSamples.header)) + SessionSamples.records.map(codec::encode))
            .joinToString("") { "$it\n" }

    /**
     * Writes the fixture of [codec]'s current version under [root] and returns its path.
     * Throws [IllegalStateException] when that version's directory already exists.
     */
    fun write(
        root: Path,
        codec: SessionCodec = SessionCodec(),
    ): Path {
        val dir = root.resolve("v${codec.currentVersion}")
        check(!Files.exists(dir)) {
            "$dir exists: a published fixture is never rewritten; a format change needs a new version"
        }
        Files.createDirectories(dir)
        return Files.writeString(dir.resolve(FILE), text(codec))
    }

    /** `args`: the `session-format` resource directory. */
    @JvmStatic
    fun main(args: Array<String>) {
        println("wrote ${write(Path.of(args.single()))}")
    }

    /** File name inside a version directory. */
    const val FILE: String = "session.jsonl"
}
