package dev.phoneagent.app

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsAppNameFromResources() {
        val appName = compose.activity.getString(R.string.app_name)
        compose.onNodeWithText(appName).assertExists()
    }
}
