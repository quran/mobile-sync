package com.quran.shared.pipeline.integration.scenarios

import com.quran.shared.auth.model.AuthState
import com.quran.shared.auth.model.UserInfo
import com.quran.shared.pipeline.integration.AVATARS_HOST
import com.quran.shared.pipeline.integration.SyncDevice
import com.quran.shared.pipeline.integration.SyncScenario
import com.quran.shared.pipeline.integration.assertSucceeded
import com.quran.shared.pipeline.integration.awaitUntil
import com.quran.shared.pipeline.integration.server.HttpError
import com.quran.shared.pipeline.integration.syncScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * The signed-in user's name and photo follow the account's profile on the server, which the website edits: a sign-in's
 * sync fetches it, and `refreshProfile()` fetches it again.
 */
class ProfileScenariosTest {
    @Test
    fun `PROFILE-01 a sign-in shows the photo uploaded on the web, fetching it once`() = syncScenario {
        web(ALICE).editProfile(firstName = "Alice", lastName = "Smith", photoUrl = ".png.1788192266")
        val phone = profileDevice("phone")

        assertSucceeded(phone.login(ALICE))
        phone.awaitUserName("Alice Smith")

        assertEquals(
            "$AVATARS_HOST/events/banner/en/avatars/alice/medium.png?v=1788192266",
            phone.user().photoUrl
        )
        assertSucceeded(phone.syncNow())
        assertEquals(1, server.profileRequests("phone").size, "a later sync doesn't fetch a profile less than a day old")
    }

    @Test
    fun `PROFILE-02 refreshProfile brings a name changed on the web`() = syncScenario {
        val phone = device("phone")
        // Without a profile to keep, the sign-in's sync fetches none, so the refresh below can't join it.
        assertSucceeded(phone.login(ALICE))
        phone.auth.storesProfiles = true

        web(ALICE).editProfile(firstName = "Alice", lastName = "B.", photoUrl = "https://lh3.googleusercontent.com/a/alice")
        phone.data.refreshProfile()

        assertEquals("Alice B.", phone.user().name)
        assertEquals("https://lh3.googleusercontent.com/a/alice", phone.user().photoUrl)
        assertEquals(1, server.profileRequests("phone").size)
    }

    @Test
    fun `PROFILE-03 a failed fetch keeps the user, the session and sync`() = syncScenario {
        server.profileFailure = HttpError(500, "ServerError", "Something went wrong", details = null)
        val phone = profileDevice("phone")
        assertSucceeded(phone.login(ALICE))
        awaitUntil("the sign-in's profile fetch") { server.profileRequests("phone").isNotEmpty() }

        web(ALICE).editProfile(firstName = "Alice", lastName = "B.")
        assertFails { phone.data.refreshProfile() }

        assertEquals(UserInfo(id = ALICE, name = ALICE), phone.user())
        assertTrue(phone.authService.isLoggedIn())
        assertSucceeded(phone.syncNow())
    }

    @Test
    fun `PROFILE-04 a sign-in to another account shows that account's profile`() = syncScenario {
        web(ALICE).editProfile(firstName = "Alice", lastName = "Smith", photoUrl = ".png.1")
        web(BOB).editProfile(firstName = "Bob", lastName = "Jones")
        val phone = profileDevice("phone")
        assertSucceeded(phone.login(ALICE))
        phone.awaitUserName("Alice Smith")

        phone.logout()
        assertSucceeded(phone.login(BOB))
        phone.awaitUserName("Bob Jones")

        assertEquals(UserInfo(id = BOB, firstName = "Bob", lastName = "Jones", name = "Bob Jones", email = "bob@example.com"), phone.user())
    }

    /** A device whose sessions keep the profiles they fetch. */
    private fun SyncScenario.profileDevice(name: String): SyncDevice =
        device(name).also { it.auth.storesProfiles = true }

    private fun SyncDevice.user(): UserInfo =
        checkNotNull((data.authState.value as? AuthState.Success)?.userInfo) { "$name is not signed in" }

    /** Waits for the sign-in's background profile fetch to publish [name]. */
    private suspend fun SyncDevice.awaitUserName(name: String) =
        awaitUntil("${this.name} shows $name") { (data.authState.value as? AuthState.Success)?.userInfo?.name == name }
}
