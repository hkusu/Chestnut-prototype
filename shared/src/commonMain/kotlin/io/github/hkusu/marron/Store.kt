package io.github.hkusu.marron

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ViewModel で保持
class MainStore(
    coroutineScope: CoroutineScope,
    ) : Store<MainState, MainAction, MainEvent>(
    initialState = MainState.Initial,
    coroutineScope = coroutineScope
) {
    override suspend fun getState(state: MainState, action: MainAction, emmit: EventEmmit<MainEvent>): MainState {
        return when(state) {
            is MainState.Loading -> {
                when(action) {
                    MainAction.OnClicked -> {
                        state
                    }
                    MainAction.OnLoaded -> {
                        state
                    }
                }
            }
            MainState.Initial -> {
                when(action) {
                    MainAction.OnClicked -> {
                        emmit(MainEvent.OnLoadedClicked)
                        state
                    }
                    else -> state
                }
            }
        }
    }
}

abstract class Store<S: State, A: Action, E: Event>(
    initialState: S,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val _state: MutableStateFlow<S> = MutableStateFlow(initialState)
    val state = _state.asStateFlow()

    private val _event: MutableSharedFlow<Event> = MutableSharedFlow()
    val event = _event.asSharedFlow()

    protected abstract suspend fun getState(state: S, action: A, emmit: EventEmmit<E>): S

    fun dispatch(action: A) {
        coroutineScope.launch {
            _state.update {
                getState(_state.value, action) {
                    _event.emit(it)
                }
            }
        }
    }

    // viseModelScope のような auto close の CoroutinesScope 以外の場合に利用
    fun dispose() {
        coroutineScope.cancel()
    }

    fun interface EventEmmit<E> {
        suspend operator fun invoke(event: E)
    }
}


sealed interface State
sealed interface Action
sealed interface Event

sealed interface MainState: State {
    data class Loading(val hoge: String): MainState
    data object Initial: MainState
}

sealed interface MainAction: Action {
     data object OnClicked: MainAction
    data object OnLoaded: MainAction
}

sealed interface MainEvent: Event {
    data object OnLoadedClicked: MainEvent
}