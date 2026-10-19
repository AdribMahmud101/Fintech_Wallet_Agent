package app.fusion.wallet.ui.shell

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import app.fusion.wallet.ui.components.NavRoute

/** Typed destinations; SavedStateHandle restores the selected screen after process recreation. */
class ShellViewModel(private val saved: SavedStateHandle) : ViewModel() {
    val destination = saved.getStateFlow("destination", NavRoute.Treasury.name)
    fun navigate(route: NavRoute) { saved["destination"] = route.name }
}
