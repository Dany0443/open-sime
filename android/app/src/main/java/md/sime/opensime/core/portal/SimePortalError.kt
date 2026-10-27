package md.sime.opensime.core.portal

import md.sime.opensime.core.model.StudentId
import md.sime.opensime.core.model.StudentMismatchException
import md.sime.opensime.core.model.StudentNotFoundException

/**
 * Canonical error codes matching `sendError` in `web/server.js:2656-2699`.
 */
enum class SimeErrorCode {
    PORTAL_THROTTLED,
    PORTAL_MAINTENANCE,
    PORTAL_FORBIDDEN,
    PORTAL_NOT_FOUND,
    STUDENT_MISMATCH,
    STUDENT_NOT_FOUND,
    PORTAL_LOGIN_REJECTED,
    APP_SESSION_INVALID,
    PORTAL_UNEXPECTED_PAGE,
    INTERNAL_ERROR,
}

/**
 * Base exception hierarchy for direct `agenda.sime.md` portal operations.
 * Mirrors the error taxonomy in `web/server.js:53-82` and `web/server.js:120-121`.
 */
sealed class SimePortalError(
    message: String,
    val code: SimeErrorCode,
    val retryAfterSeconds: Int? = null,
    cause: Throwable? = null,
) : Exception(SimeLogScrubber.scrubString(message), cause) {

    override fun toString(): String =
        "${this::class.simpleName}(code=$code, message=${SimeLogScrubber.scrubString(message.orEmpty())})"

    companion object {
        const val DEFAULT_WAIT_SECONDS: Int = 900
        const val MAX_WAIT_SECONDS: Int = 3600

        /**
         * Normalizes any [Throwable] (including domain [StudentMismatchException] and
         * [StudentNotFoundException]) into a typed [SimePortalError].
         */
        fun fromThrowable(throwable: Throwable): SimePortalError = when (throwable) {
            is SimePortalError -> throwable
            is StudentMismatchException -> SimeStudentMismatchError(
                message = throwable.message ?: "Portalul a returnat alt elev decât cel selectat.",
                requestedStudentId = throwable.requestedStudentId,
                actualStudentId = throwable.actualStudentId,
                cause = throwable,
            )
            is StudentNotFoundException -> SimeStudentNotFoundError(
                message = throwable.message ?: "Elevul nu a fost găsit.",
                studentId = throwable.requestedStudentId,
                cause = throwable,
            )
            else -> SimeNetworkError(
                message = throwable.message ?: "Eroare la comunicarea cu portalul.",
                cause = throwable,
            )
        }
    }
}

class SimeNetworkError(
    message: String = "Network error",
    cause: Throwable? = null,
) : SimePortalError(
    message = message,
    code = SimeErrorCode.PORTAL_UNEXPECTED_PAGE,
    cause = cause,
)

open class SimeAuthError(
    message: String,
    code: SimeErrorCode = SimeErrorCode.PORTAL_UNEXPECTED_PAGE,
    cause: Throwable? = null,
) : SimePortalError(
    message = message,
    code = code,
    cause = cause,
)

class SimeAppSessionError(
    message: String = "Sesiunea a expirat. Autentifică-te din nou.",
    cause: Throwable? = null,
) : SimeAuthError(
    message = message,
    code = SimeErrorCode.APP_SESSION_INVALID,
    cause = cause,
)

class SimeLoginRejectedError(
    message: String = "Numele de utilizator sau parola sunt incorecte.",
    cause: Throwable? = null,
) : SimeAuthError(
    message = message,
    code = SimeErrorCode.PORTAL_LOGIN_REJECTED,
    cause = cause,
)

class SimeSessionExpiredError(
    message: String = "Sesiunea portalului nu a putut fi reînnoită.",
    cause: Throwable? = null,
) : SimeAuthError(
    message = message,
    code = SimeErrorCode.PORTAL_UNEXPECTED_PAGE,
    cause = cause,
)

class SimeRateLimitError(
    message: String = "Portalul școlii a limitat solicitările. Încearcă din nou mai târziu.",
    waitSeconds: Int = DEFAULT_WAIT_SECONDS,
    cause: Throwable? = null,
) : SimePortalError(
    message = message,
    code = SimeErrorCode.PORTAL_THROTTLED,
    retryAfterSeconds = waitSeconds.coerceIn(1, MAX_WAIT_SECONDS),
    cause = cause,
) {
    val waitSeconds: Int get() = retryAfterSeconds ?: DEFAULT_WAIT_SECONDS
}

class SimeMaintenanceError(
    message: String = "Portalul este în mentenanță",
    waitSeconds: Int = DEFAULT_WAIT_SECONDS,
    cause: Throwable? = null,
) : SimePortalError(
    message = message,
    code = SimeErrorCode.PORTAL_MAINTENANCE,
    retryAfterSeconds = waitSeconds.coerceIn(1, MAX_WAIT_SECONDS),
    cause = cause,
) {
    val waitSeconds: Int get() = retryAfterSeconds ?: DEFAULT_WAIT_SECONDS
}

class SimeForbiddenError(
    message: String = "Acces interzis de portalul școlii (posibil blocare de securitate).",
    cause: Throwable? = null,
) : SimePortalError(
    message = message,
    code = SimeErrorCode.PORTAL_FORBIDDEN,
    cause = cause,
)

class SimeNotFoundError(
    message: String = "Pagina solicitată nu a fost găsită pe portalul școlii.",
    cause: Throwable? = null,
) : SimePortalError(
    message = message,
    code = SimeErrorCode.PORTAL_NOT_FOUND,
    cause = cause,
)

class SimeUnexpectedPageError(
    message: String = "Portalul a returnat o pagină neașteptată.",
    val step: String? = null,
    cause: Throwable? = null,
) : SimePortalError(
    message = message,
    code = SimeErrorCode.PORTAL_UNEXPECTED_PAGE,
    cause = cause,
)

class SimeParseError(
    message: String = "Portalul a returnat un răspuns neașteptat.",
    cause: Throwable? = null,
) : SimePortalError(
    message = message,
    code = SimeErrorCode.PORTAL_UNEXPECTED_PAGE,
    cause = cause,
)

open class SimeStudentNotFoundError(
    message: String = "Elevul nu a fost găsit.",
    val studentId: StudentId? = null,
    code: SimeErrorCode = SimeErrorCode.STUDENT_NOT_FOUND,
    cause: Throwable? = null,
) : SimePortalError(
    message = message,
    code = code,
    cause = cause,
)

/**
 * The portal answered with a different student than the one requested; never serve that page.
 * Mirrors `SimeStudentMismatchError` in `web/server.js:82`.
 */
class SimeStudentMismatchError(
    message: String = "Portalul a returnat alt elev decât cel selectat.",
    val requestedStudentId: StudentId? = null,
    val actualStudentId: StudentId? = null,
    cause: Throwable? = null,
) : SimeStudentNotFoundError(
    message = message,
    studentId = requestedStudentId,
    code = SimeErrorCode.STUDENT_MISMATCH,
    cause = cause,
)

