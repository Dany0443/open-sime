package md.sime.opensime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM truth-table for the root routes. The `when` in OpenSimeApp checks the Login route
 * before the walkthrough route, exactly as these helpers are combined below.
 */
class OpenSimeRoutingTest {

    private fun login(
        active: Boolean = false, injected: Boolean = false, showLogin: Boolean = false,
        reopened: Boolean = false, completed: Boolean = true,
    ) = isLoginRoute(active, injected, showLogin, reopened, completed)

    private fun walkthrough(
        active: Boolean = false, injected: Boolean = false,
        reopened: Boolean = false, completed: Boolean = true,
    ) = isWalkthroughRoute(active, injected, reopened, completed)

    @Test fun firstLaunch_showsWalkthrough_notLogin() {
        assertFalse(login(completed = false))
        assertTrue(walkthrough(completed = false))
    }

    @Test fun completedOnboarding_withoutSession_showsLoginDirectly() {
        assertTrue(login(completed = true))
        assertFalse(walkthrough(completed = true))
    }

    @Test fun afterLogout_showsLogin_notALegacyFallback() {
        // handleSignOut sets showLoginScreen = true; the default route must agree even if it is lost.
        assertTrue(login(showLogin = true))
        assertTrue(login(showLogin = false))
        assertFalse(walkthrough())
    }

    @Test fun reopenedWalkthrough_winsOverLogin_untilDismissed() {
        assertFalse(login(reopened = true, showLogin = true))
        assertTrue(walkthrough(reopened = true))
        // Back / Continue clears the flag and Login is the route again.
        assertTrue(login(reopened = false))
    }

    @Test fun staleReopenedFlag_isIgnoredWhileAppIsAuthenticated() {
        assertFalse(walkthrough(active = true, reopened = true))
        assertTrue(login(active = true, showLogin = true, reopened = true)) // sign-in overlay still works
        assertFalse(login(active = true, showLogin = false, reopened = true)) // app shell
    }

    @Test fun loginOverlayOverAuthenticatedApp_isUnchanged() {
        assertTrue(login(active = true, showLogin = true))
        assertFalse(login(active = true, showLogin = false))
    }

    @Test fun injectedRepository_neverShowsWalkthrough_evenAfterLogoutWithIncompleteOnboarding() {
        assertFalse(walkthrough(injected = true, completed = false))
        assertTrue(login(injected = true, completed = false))
    }

    /** The legacy screen is gone only if every no-repository state still renders Login or the walkthrough. */
    @Test fun noRepository_alwaysHasARoute_soThereIsNoFallthrough() {
        val b = listOf(false, true)
        for (injected in b) for (showLogin in b) for (reopened in b) for (completed in b) {
            val l = login(false, injected, showLogin, reopened, completed)
            val w = walkthrough(false, injected, reopened, completed)
            assertTrue("fell through: injected=$injected showLogin=$showLogin reopened=$reopened completed=$completed", l || w)
        }
    }
}
