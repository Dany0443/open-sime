package md.sime.opensime

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Runs against the separate debug package; never submits credentials or signs out. */
class LoginActivityRecreationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun recreationClearsPasswordAndPreservesUncheckedChoice() {
        fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        compose.waitUntil(5_000) {
            exists("onboarding_action_continue") || exists("signed_out_action_signin") ||
                exists("auth_username_input") || exists("compact_bottom_nav")
        }
        // Preserve any authenticated debug session if this test is run on a developer's installation.
        assumeTrue(exists("onboarding_action_continue") || exists("signed_out_action_signin") || exists("auth_username_input"))
        if (exists("onboarding_action_continue")) compose.onNodeWithTag("onboarding_action_continue").performScrollTo().performClick()
        if (exists("signed_out_action_signin")) compose.onNodeWithTag("signed_out_action_signin").performClick()
        compose.onNodeWithTag("auth_username_input").performTextInput("synthetic-user")
        compose.onNodeWithTag("auth_password_input").performTextInput("synthetic-password")
        compose.onNodeWithTag("auth_remember_me_checkbox").performScrollTo().performClick().assertIsOff()

        compose.activityRule.scenario.recreate()

        compose.onNodeWithTag("auth_username_input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("synthetic-user")))
        compose.onNodeWithTag("auth_password_input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("auth_remember_me_checkbox").performScrollTo().assertIsOff()
    }
}
