package dev.phoneagent.app.adapters.screen

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText

/**
 * A page of the test APK with one focused field holding [OLD]: a password field when the intent's
 * [PASSWORD] extra is `true`, else a plain one, whose text accessibility shows.
 */
class FieldActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val variation =
            if (intent.getBooleanExtra(PASSWORD, false)) InputType.TYPE_TEXT_VARIATION_PASSWORD else 0
        val field =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or variation
                setText(OLD)
            }
        setContentView(field)
        field.requestFocus()
    }

    companion object {
        /** What the field holds when the page opens. */
        const val OLD: String = "oldpw"

        /** Boolean extra: the field is a password field. */
        const val PASSWORD: String = "password"
    }
}
