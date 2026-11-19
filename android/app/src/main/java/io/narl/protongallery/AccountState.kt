package io.narl.protongallery

import io.narl.protongallery.bridge.AccountStatus
import io.narl.protongallery.bridge.SignInStage

data class AccountState(
    val status: AccountStatus = AccountStatus(SignInStage.SIGNED_OUT, "", null, false),
    val busy: Boolean = false,
    val error: String? = null,
    val visible: Boolean = false,
    // Proton Photos sync and the Drive gallery sync run independently.
    val syncing: Boolean = false,
    val indexed: Long = 0,
    val lastPhotosSync: Long? = null,
    val sessionExpired: Boolean = false,
    // The gallery being added or refreshed, e.g. "Holidays" or "your galleries".
    val driveSync: String? = null,
    val driveIndexed: Long = 0,
    // Changes on account boundaries; clears remote previews and paging sessions.
    val generation: Int = 0,
    val visibilityReady: Boolean = true,
    val visibilityStale: Boolean = false,
    val visibilityRevision: Int = 0,
    // Plain-text connection check a tester can copy; no secrets.
    val report: String? = null,
    val checking: Boolean = false,
) {
    val connected get() = status.stage == SignInStage.CONNECTED
}
