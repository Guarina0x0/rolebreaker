package com.authzmatrix.core

/**
 * Safety policy for the automatic replay modes. By default they only touch idempotent methods
 * and skip clearly destructive URLs; the user can explicitly opt into state-changing requests.
 */
object SweepPolicy {

    private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")

    private val DANGEROUS_PATH = Regex(
        """(?i)/(logout|log-out|signout|sign-out|sign_out|delete|remove|destroy|revoke|deactivate|reset-password|change-password)([/_-]|$)""",
    )

    fun isSafeMethod(method: String): Boolean = method.uppercase() in SAFE_METHODS

    fun isDangerousUrl(url: String): Boolean {
        // Only the path matters, and it is matched without the query string or fragment.
        val path = url.substringBefore('#').substringBefore('?')
        return DANGEROUS_PATH.containsMatchIn(path)
    }

    fun isSafe(method: String, url: String): Boolean =
        isSafeMethod(method) && !isDangerousUrl(url)
}

/** Cancellation handle shared by all tasks of one sweep. */
class SweepToken {
    @Volatile
    var cancelled: Boolean = false
}

/** Result of one sweep task, used to keep the progress counters accurate. */
enum class TestOutcome { TESTED, SKIPPED, ERROR }

/** Immutable progress snapshot for the UI. */
data class SweepProgress(
    val total: Int,
    val done: Int,
    val tested: Int,
    val errors: Int,
    val skipped: Int,
)
