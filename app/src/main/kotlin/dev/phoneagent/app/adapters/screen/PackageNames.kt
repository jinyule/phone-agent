package dev.phoneagent.app.adapters.screen

/**
 * Java package names as Android packages have them, for names from the model and from the
 * package manager. Checked part by part: a regex that repeats a group (`(\.part)+`) recurses
 * once per part on the JVM, and a few thousand parts overflow the stack.
 */
internal object PackageNames {
    private val PART = Regex("[A-Za-z][A-Za-z0-9_]*")

    /** Whether [name] is two parts or more joined by dots, each a letter, then letters, digits or `_`. */
    fun isValid(name: String): Boolean {
        val parts = name.split('.')
        return parts.size >= 2 && parts.all(PART::matches)
    }
}
