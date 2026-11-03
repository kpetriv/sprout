package dev.kpetriv.sprout.settings

import dev.kpetriv.sprout.core.syncfirebase.FunctionsError
import dev.kpetriv.sprout.core.syncfirebase.FunctionsException
import dev.kpetriv.sprout.feature.settings.AdminException
import dev.kpetriv.sprout.feature.settings.AdminFailure
import org.junit.Test
import kotlin.test.assertEquals

/** Callable failures as the settings screens see them. */
class HouseholdAdaptersTest {
    private fun mapped(error: FunctionsError, precondition: AdminFailure = AdminFailure.NOT_ALLOWED) = (
        Result.failure<Unit>(FunctionsException(error)).toAdmin(precondition = precondition).exceptionOrNull()
            as AdminException
        ).failure

    @Test
    fun `a deletion refused for an old sign-in asks to sign in again`() {
        assertEquals(
            AdminFailure.REAUTH_REQUIRED,
            mapped(FunctionsError.FailedPrecondition, precondition = AdminFailure.REAUTH_REQUIRED),
        )
    }

    @Test
    fun `other refusals keep their meaning`() {
        assertEquals(AdminFailure.NOT_ALLOWED, mapped(FunctionsError.FailedPrecondition))
        assertEquals(AdminFailure.NOT_ALLOWED, mapped(FunctionsError.PermissionDenied))
        assertEquals(AdminFailure.NETWORK, mapped(FunctionsError.Unavailable))
        assertEquals(AdminFailure.RATE_LIMITED, mapped(FunctionsError.ResourceExhausted))
        assertEquals(AdminFailure.FAILED, mapped(FunctionsError.Unknown("internal")))
    }
}
