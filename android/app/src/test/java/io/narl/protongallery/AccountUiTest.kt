package io.narl.protongallery

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import io.narl.protongallery.bridge.AccountStatus
import io.narl.protongallery.bridge.SignInStage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xxhdpi")
class AccountUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun native_sign_in_validates_inputs_and_clears_the_submitted_password() {
        var submitted: Pair<String,String>? = null
        render(AccountState(visible = true), signIn = { username, password -> submitted = username to password })
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("build/screenshots/sign-in-dark.png")
        compose.onNodeWithText("Email or username").performTextInput("fixture-user")
        compose.onNodeWithText("Password").performTextInput("fixture-password")
        compose.onNodeWithText("Sign in").performClick()
        assertEquals("fixture-user" to "fixture-password", submitted)
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
    }
    @Test fun code_step_requires_six_digits_and_keeps_a_clear_cancel_path() {
        var code = ""
        render(AccountState(status = AccountStatus(SignInStage.SECOND_FACTOR, "fixture-user", null, false), visible = true), code = { code = it })
        compose.onNodeWithText("Authenticator code").performTextInput("12x34")
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Authenticator code").performTextInput("56")
        compose.onNodeWithText("Continue").performClick()
        assertEquals("123456", code)
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
    @Test fun mailbox_errors_are_visible_and_sign_in_has_a_proton_styled_layout() {
        render(AccountState(status = AccountStatus(SignInStage.MAILBOX_PASSWORD, "fixture-user", null, false), visible = true,
            error = "That password didn’t unlock your account."))
        compose.onNodeWithText("Mailbox password").assertIsDisplayed()
        compose.onNodeWithText("That password didn’t unlock your account.").assertIsDisplayed()
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("build/screenshots/sign-in-mailbox-dark.png")
    }
    @Test fun a_failed_sign_in_offers_a_connection_check_with_a_copyable_report() {
        var tested = 0
        render(AccountState(visible = true, error = "Couldn’t reach Proton. Check your connection and try again."), test = { tested++ })
        compose.onNodeWithText("Test connection").performClick()
        assertEquals(1, tested)
        val report = "Certificate check: ready\nName lookup: 1 IPv4, 0 IPv6 in 5 ms\nProton API: FAILED after 80 ms: invalid peer certificate"
        render(AccountState(visible = true, error = "Couldn’t reach Proton. Check your connection and try again.", report = report))
        compose.onNodeWithText(report).assertExists()
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("Test again").assertIsDisplayed()
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("build/screenshots/sign-in-connection-report-dark.png")
    }
    @Test fun verification_accepts_only_the_proton_origin_and_captcha_success_payload() {
        assertTrue(trustedVerificationUrl("https://verify.proton.me/?embed=1"))
        listOf("http://verify.proton.me", "https://verify.proton.me.evil.test/", "https://evil.test@verify.proton.me/", "https://verify.proton.me:444/").forEach { assertFalse(it, trustedVerificationUrl(it)) }
        assertEquals("answer", verificationToken("""{"type":"HUMAN_VERIFICATION_SUCCESS","payload":{"type":"captcha","token":"answer"}}"""))
        assertNull(verificationToken("""{"type":"HUMAN_VERIFICATION_SUCCESS","payload":{"type":"email","token":"answer"}}"""))
        assertNull(verificationToken("""{"type":"ERROR","payload":{"token":"answer"}}"""))
    }
    @Test fun a_signed_in_account_explains_how_to_add_proton_photos_to_the_system_picker() {
        render(AccountState(status = AccountStatus(SignInStage.CONNECTED, "fixture-user", null, false), visible = true))
        compose.onNodeWithText("Android photo picker").performScrollTo().assertIsDisplayed()
        // Robolectric's media provider allows no cloud apps, like most phones.
        compose.onNodeWithText("adb shell device_config put mediaprovider allowed_cloud_providers io.narl.protongallery")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Copy command").assertExists()
        compose.onAllNodes(isRoot()).onLast().captureRoboImage("build/screenshots/photo-picker-setup-dark.png")
    }
    @Test fun the_adb_command_matches_each_android_version() {
        assertEquals("adb shell device_config override mediaprovider allowed_cloud_providers app", PhotoPicker.command("app", 35))
        assertEquals("adb shell device_config put mediaprovider allowed_cloud_providers app", PhotoPicker.command("app", 34))
        assertEquals("adb shell device_config put storage_native_boot allowed_cloud_providers app.picker\n" +
            "adb shell device_config put mediaprovider allowed_cloud_providers app", PhotoPicker.command("app", 33))
    }
    private var current: androidx.compose.runtime.MutableState<AccountState>? = null
    private fun render(state: AccountState, signIn: (String,String)->Unit = {_,_->}, code:(String)->Unit = {}, test: () -> Unit = {}) {
        current?.let { it.value = state; return }
        val holder = androidx.compose.runtime.mutableStateOf(state).also { current = it }
        compose.setContent { GalleryTheme(ThemeMode.Dark) { AccountScreen(holder.value, {}, signIn, code, {}, {}, {}, {}, test) } }
    }
}
