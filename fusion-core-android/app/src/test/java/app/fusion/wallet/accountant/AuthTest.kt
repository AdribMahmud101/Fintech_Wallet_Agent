package app.fusion.wallet.accountant

import app.fusion.wallet.accountant.data.AvatarIcon
import app.fusion.wallet.accountant.data.UserEntity
import app.fusion.wallet.accountant.data.UserRole
import app.fusion.wallet.auth.AuthService
import app.fusion.wallet.auth.Caps
import app.fusion.wallet.auth.DemoUsers
import app.fusion.wallet.auth.PasswordHasher
import app.fusion.wallet.auth.SignInResult
import app.fusion.wallet.auth.UserLookup
import app.fusion.wallet.auth.caps
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTest {
    private fun user(email: String, password: String, role: UserRole = UserRole.OWNER): UserEntity {
        val salt = PasswordHasher.newSalt()
        return UserEntity(
            id = 1, email = email, displayName = "Test", role = role, company = "Co", avatarColor = 0, avatarIcon = AvatarIcon.PERSON,
            pwSalt = PasswordHasher.hex(salt), pwHash = PasswordHasher.hex(PasswordHasher.hash(password, salt, 1_000)),
            pwIterations = 1_000, createdAt = 0,
        )
    }

    private var now = 1_000L
    private fun service(vararg users: UserEntity, max: Int = 5) =
        AuthService(UserLookup { e -> users.firstOrNull { it.email == e } }, clock = { now }, maxFails = max, lockSeconds = 30)

    @Test fun correct_password_signs_in_and_email_is_case_and_space_insensitive() = runTest {
        val s = service(user("mei@fusion.demo", "Owner#2026"))
        val r = s.signIn("  Mei@Fusion.Demo ", "Owner#2026")
        assertTrue(r is SignInResult.Success)
    }

    @Test fun wrong_password_and_unknown_email_look_identical() = runTest {
        val s = service(user("mei@fusion.demo", "Owner#2026"))
        assertEquals(SignInResult.InvalidCredentials, s.signIn("mei@fusion.demo", "nope"))
        assertEquals(SignInResult.InvalidCredentials, s.signIn("ghost@fusion.demo", "Owner#2026"))
    }

    @Test fun empty_or_malformed_input_is_rejected_before_any_lookup() = runTest {
        val s = service()
        assertEquals(SignInResult.InvalidInput, s.signIn("", "x"))
        assertEquals(SignInResult.InvalidInput, s.signIn("not-an-email", "x"))
        assertEquals(SignInResult.InvalidInput, s.signIn("a@b.c", ""))
    }

    @Test fun five_wrong_passwords_lock_the_account_even_for_the_right_password_until_it_expires() = runTest {
        val s = service(user("mei@fusion.demo", "Owner#2026"))
        repeat(4) { assertEquals(SignInResult.InvalidCredentials, s.signIn("mei@fusion.demo", "bad$it")) }
        val fifth = s.signIn("mei@fusion.demo", "bad5")
        assertEquals(SignInResult.LockedOut(30), fifth)
        now += 10
        val during = s.signIn("mei@fusion.demo", "Owner#2026")
        assertTrue("locked even with the right password", during is SignInResult.LockedOut)
        assertEquals(20L, (during as SignInResult.LockedOut).seconds)
        now += 25
        assertTrue(s.signIn("mei@fusion.demo", "Owner#2026") is SignInResult.Success)
    }

    @Test fun a_success_resets_the_failure_count() = runTest {
        val s = service(user("mei@fusion.demo", "Owner#2026"))
        repeat(4) { s.signIn("mei@fusion.demo", "bad") }
        assertTrue(s.signIn("mei@fusion.demo", "Owner#2026") is SignInResult.Success)
        repeat(4) { assertEquals(SignInResult.InvalidCredentials, s.signIn("mei@fusion.demo", "bad")) }
    }

    @Test fun lockout_is_per_email() = runTest {
        val s = service(user("a@x.demo", "pw-a"), user("b@x.demo", "pw-b").copy(id = 2))
        repeat(5) { s.signIn("a@x.demo", "bad") }
        assertTrue(s.signIn("b@x.demo", "pw-b") is SignInResult.Success)
    }

    @Test fun hashes_are_salted_and_never_contain_the_password() {
        val a = user("a@x.demo", "same-password")
        val b = user("b@x.demo", "same-password")
        assertNotEquals(a.pwSalt, b.pwSalt)
        assertNotEquals(a.pwHash, b.pwHash)
        assertFalse(a.pwHash.contains("same-password"))
        assertEquals(64, a.pwHash.length) // 256-bit key as hex
    }

    @Test fun the_demo_accounts_use_distinct_passwords_roles_and_emails() {
        val a = DemoUsers.accounts
        assertEquals(3, a.size)
        assertEquals(3, a.map { it.email }.toSet().size)
        assertEquals(3, a.map { it.password }.toSet().size)
        assertEquals(setOf(UserRole.OWNER, UserRole.MANAGER, UserRole.AUDITOR), a.map { it.role }.toSet())
        assertTrue(a.all { it.avatarColor in 0..5 })
    }

    @Test fun roles_grant_progressively_fewer_powers() {
        assertEquals(Caps(true, true, true), UserRole.OWNER.caps())
        assertEquals(Caps(false, true, false), UserRole.MANAGER.caps())
        assertEquals(Caps.NONE, UserRole.AUDITOR.caps())
    }
}
