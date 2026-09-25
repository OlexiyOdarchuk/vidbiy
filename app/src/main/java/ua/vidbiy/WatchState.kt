package ua.vidbiy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Phase { IDLE, WAITING_ALERT, ALERT, RINGING, SNOOZED }

data class WatchState(
    val phase: Phase = Phase.IDLE,
    val text: String = "",
    val reason: String = "",
)

object WatchRepo {
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state

    fun set(state: WatchState) {
        _state.value = state
    }
}
