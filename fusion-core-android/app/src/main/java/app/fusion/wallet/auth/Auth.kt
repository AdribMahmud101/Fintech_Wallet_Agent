package app.fusion.wallet.auth

import app.fusion.wallet.accountant.data.AvatarIcon
import app.fusion.wallet.accountant.data.UserDao
import app.fusion.wallet.accountant.data.UserEntity
import app.fusion.wallet.accountant.data.UserRole
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * What each role may do in the UI. This is a convenience layer: the money limits themselves are
 * enforced by the on-chain wallet and the signer, never by who is logged in to the app.
 */
data class Caps(
    /** Turn cloud AI help on/off, set the API key, model and limit. */
    val manageCloud: Boolean,
    /** Pause the agent / cancel a queued action. */
    val controlAgent: Boolean,
    /** Approve an above-limit action. */
    val approveLarge: Boolean,
) {
    companion object {
        val NONE = Caps(manageCloud = false, controlAgent = false, approveLarge = false)
    }
}

fun UserRole.caps(): Caps = when (this) {
    UserRole.OWNER -> Caps(manageCloud = true, controlAgent = true, approveLarge = true)
    UserRole.MANAGER -> Caps(manageCloud = false, controlAgent = true, approveLarge = false)
    UserRole.AUDITOR -> Caps.NONE
}

/** Salted PBKDF2. Plain-text passwords are never stored or logged. */
object PasswordHasher {
    const val ITERATIONS = 60_000
    private const val KEY_BITS = 256
    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    fun hash(password: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            val f = try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            } catch (e: java.security.NoSuchAlgorithmException) {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1") // API < 26
            }
            f.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Constant-time comparison. */
    fun verify(password: String, user: UserEntity): Boolean =
        MessageDigest.isEqual(hash(password, unhex(user.pwSalt), user.pwIterations), unhex(user.pwHash))

    /** Burns the same work as a real check so an unknown e-mail is not distinguishable by timing. */
    fun burn(password: String) {
        hash(password, ByteArray(16), ITERATIONS)
    }
}

fun interface UserLookup {
    suspend fun byEmail(email: String): UserEntity?
}

sealed interface SignInResult {
    data class Success(val user: UserEntity) : SignInResult
    data object InvalidInput : SignInResult
    data object InvalidCredentials : SignInResult
    data class LockedOut(val seconds: Long) : SignInResult
}

/**
 * Checks e-mail + password against stored hashes. After [maxFails] wrong passwords for an e-mail it
 * refuses further attempts for [lockSeconds]. The error for a wrong password and an unknown e-mail
 * is the same on purpose.
 */
class AuthService(
    private val users: UserLookup,
    private val clock: () -> Long,
    private val maxFails: Int = 5,
    private val lockSeconds: Long = 30,
) {
    private val fails = HashMap<String, Int>()
    private val lockedUntil = HashMap<String, Long>()

    suspend fun signIn(emailRaw: String, password: String): SignInResult {
        val email = emailRaw.trim().lowercase()
        if (email.isEmpty() || !email.contains('@') || password.isEmpty()) return SignInResult.InvalidInput

        synchronized(this) {
            lockedUntil[email]?.let { until ->
                val left = until - clock()
                if (left > 0) return SignInResult.LockedOut(left) else lockedUntil.remove(email)
            }
        }
        val user = users.byEmail(email)
        val ok = if (user != null) PasswordHasher.verify(password, user) else { PasswordHasher.burn(password); false }
        synchronized(this) {
            if (ok) {
                fails.remove(email)
                lockedUntil.remove(email)
                return SignInResult.Success(user!!)
            }
            val n = (fails[email] ?: 0) + 1
            if (n >= maxFails) {
                lockedUntil[email] = clock() + lockSeconds
                fails.remove(email)
                return SignInResult.LockedOut(lockSeconds)
            }
            fails[email] = n
        }
        return SignInResult.InvalidCredentials
    }
}

/**
 * Demo accounts for the hackathon. They hold NO financial data and unlock nothing on-chain; they exist
 * so judges can see role-aware behaviour. Credentials are public on purpose (this repository is public).
 */
data class DemoAccount(
    val email: String,
    val password: String,
    val name: String,
    val role: UserRole,
    val company: String,
    val avatarColor: Int,
    val avatarIcon: AvatarIcon,
)

object DemoUsers {
    // Every default account starts with the plain "person" avatar; colour tells them apart and users can customise it.
    val accounts = listOf(
        DemoAccount("mei@fusion.demo", "Owner#2026", "Lin Mei", UserRole.OWNER, "Mei Trading Co.", 0, AvatarIcon.PERSON),
        DemoAccount("wei@fusion.demo", "Manager#2026", "Chen Wei", UserRole.MANAGER, "Mei Trading Co.", 2, AvatarIcon.PERSON),
        DemoAccount("judge@fusion.demo", "Auditor#2026", "Guest Judge", UserRole.AUDITOR, "Mei Trading Co.", 4, AvatarIcon.PERSON),
    )

    /** Inserts any missing demo account (idempotent). */
    suspend fun provision(dao: UserDao, now: Long) {
        for (a in accounts) {
            if (dao.byEmail(a.email) != null) continue
            val salt = PasswordHasher.newSalt()
            dao.insert(
                UserEntity(
                    email = a.email, displayName = a.name, role = a.role, company = a.company,
                    avatarColor = a.avatarColor, avatarIcon = a.avatarIcon,
                    pwSalt = PasswordHasher.hex(salt),
                    pwHash = PasswordHasher.hex(PasswordHasher.hash(a.password, salt)),
                    pwIterations = PasswordHasher.ITERATIONS, createdAt = now,
                ),
            )
        }
    }
}
