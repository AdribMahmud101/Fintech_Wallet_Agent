package app.fusion.wallet.ui.auth

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fusion.wallet.accountant.AccountantServices
import app.fusion.wallet.accountant.data.AvatarIcon
import app.fusion.wallet.accountant.data.UserEntity
import app.fusion.wallet.auth.AuthService
import app.fusion.wallet.auth.DemoUsers
import app.fusion.wallet.auth.SignInResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AuthError { INVALID_INPUT, INVALID_CREDENTIALS, LOCKED }

data class AuthState(
    /** True until the saved session (if any) has been restored; the splash covers this. */
    val checking: Boolean = true,
    val user: UserEntity? = null,
    val busy: Boolean = false,
    val error: AuthError? = null,
    /** Epoch seconds until which sign-in is refused, when [error] is LOCKED. */
    val lockedUntil: Long = 0,
)

/** Demo sign-in. Only a user id is persisted, never a password. */
class AuthViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AccountantServices.get(app).db.userDao()
    private val prefs = app.getSharedPreferences("fusion_prefs", Context.MODE_PRIVATE)
    private val service = AuthService({ email -> dao.byEmail(email) }, clock = { System.currentTimeMillis() / 1000 })

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()
    private var observing: Job? = null

    init {
        viewModelScope.launch {
            withContext(Dispatchers.Default) { DemoUsers.provision(dao, System.currentTimeMillis() / 1000) }
            val saved = prefs.getLong(KEY_UID, -1L)
            if (saved > 0 && dao.byId(saved) != null) observe(saved)
            _state.update { it.copy(checking = false) }
        }
    }

    fun signIn(email: String, password: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = withContext(Dispatchers.Default) { service.signIn(email, password) }
            when (result) {
                is SignInResult.Success -> {
                    prefs.edit().putLong(KEY_UID, result.user.id).apply()
                    observe(result.user.id)
                    _state.update { it.copy(busy = false, error = null) }
                }
                SignInResult.InvalidInput -> _state.update { it.copy(busy = false, error = AuthError.INVALID_INPUT) }
                SignInResult.InvalidCredentials -> _state.update { it.copy(busy = false, error = AuthError.INVALID_CREDENTIALS) }
                is SignInResult.LockedOut -> _state.update {
                    it.copy(busy = false, error = AuthError.LOCKED, lockedUntil = System.currentTimeMillis() / 1000 + result.seconds)
                }
            }
        }
    }

    fun signOut() {
        prefs.edit().remove(KEY_UID).apply()
        observing?.cancel()
        _state.update { it.copy(user = null, error = null, busy = false) }
    }

    fun setAvatar(color: Int, icon: AvatarIcon) {
        val u = _state.value.user ?: return
        viewModelScope.launch { dao.setAvatar(u.id, color.coerceIn(0, 5), icon) }
    }

    fun setName(name: String) {
        val u = _state.value.user ?: return
        val clean = name.trim().take(40)
        if (clean.isNotEmpty()) viewModelScope.launch { dao.setName(u.id, clean) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Follow the stored row so avatar/name edits show everywhere immediately. */
    private fun observe(id: Long) {
        observing?.cancel()
        observing = viewModelScope.launch { dao.observe(id).collect { u -> _state.update { it.copy(user = u) } } }
    }

    private companion object {
        const val KEY_UID = "uid"
    }
}
