package io.github.hkusu.marron

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ViewModel で保持（ KMP プロジェクトでない場合は ViewModel に implement してもよし
class MainStore(
    // UseCase や Repository を inject
    //   getHogeListUseCase: GetHogeListUseCase,
    //   setgFugaUseCase: SetFugaUseCase,
    coroutineScope: CoroutineScope, // 基本は viewModelScope を渡す想定
) : Store<MainState, MainAction, MainEvent>(
    initialState = MainState.Initial,
    enterAction = MainAction.Enter,
    exitAction = MainAction.Exit,
    coroutineScope = coroutineScope,
) {
    override suspend fun onDispatched(state: MainState, action: MainAction, emmit: EventEmmit<MainEvent>): MainState = when (state) {
        MainState.Initial -> when (action) {
            MainAction.Enter -> {
                // すぐさま Loading に
                MainState.Loading
            }

            else -> null
        }

        MainState.Loading -> when (action) {
            MainAction.Enter -> {
                // UseCase や Repository からデータ取得
                delay(5_000)
                // データを読み終わったら Stable に
                MainState.Stable(listOf())
            }

            else -> null
        }

        is MainState.Stable -> when (action) {
            MainAction.Enter -> {
                // Compose で state.dataList のデータを画面へ描画する

                // イベント発行例
                emmit(MainEvent.ShowToast("データがロードされました"))
                null
            }

            is MainAction.Click -> {
                // state の更新は data class の copy で
                state.copy(clickCounter = state.clickCounter + 1)
            }

            else -> null
        }

    } ?: state
}

abstract class Store<S : State, A : Action, E : Event>(
    initialState: S,
    private val enterAction: A?,
    private val exitAction: A?,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val _state: MutableStateFlow<S> = MutableStateFlow(initialState)
    val state = _state.asStateFlow() // Compose で購読

    private val _event: MutableSharedFlow<Event> = MutableSharedFlow()
    val event = _event.asSharedFlow() // Compose で購読

    protected abstract suspend fun onDispatched(state: S, action: A, emmit: EventEmmit<E>): S

    init {
        enterAction?.let { dispatch(it) }
    }

    fun dispatch(action: A) { // ユーザによる操作. Compose の画面から叩く
        val prevState = _state.value
        coroutineScope.launch {
            val nextState = onDispatched(prevState, action) { event ->
                _event.emit(event)
            }

            if (prevState::class.qualifiedName != nextState::class.qualifiedName) {
                exitAction?.let {
                    onDispatched(prevState, it) { event ->
                        _event.emit(event)
                    }
                }
            }

            _state.update { nextState }

            if (prevState::class.qualifiedName != nextState::class.qualifiedName) {
                enterAction?.let { dispatch(it) }
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

sealed interface Contract

sealed interface State : Contract
sealed interface Action : Contract
sealed interface Event : Contract

sealed interface MainState : State {
    data object Initial : MainState
    data object Loading : MainState
    data class Stable(
        val dataList: List<String>,
        val clickCounter: Int = 0,
    ) : MainState
}

sealed interface MainAction : Action {
    data object Enter : MainAction
    data object Exit : MainAction
    data class Click(val id: Long) : MainAction
}

sealed interface MainEvent : Event {
    data class ShowToast(val message: String) : MainEvent
}
