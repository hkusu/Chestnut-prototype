package io.github.hkusu.marron

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ViewModel で保持（ KMP プロジェクトでない場合は ViewModel に Store を implement してもよし
class MainStore(
    // UseCase や Repository を inject
    //   getHogeListUseCase: GetHogeListUseCase,
    //   setgFugaUseCase: SetFugaUseCase,
    coroutineScope: CoroutineScope, // 基本は viewModelScope を渡す想定
    // 外から Middleware を渡す場合
    //    override val middlewares: List<Middleware<MainState, MainAction, MainEvent>>
) : DefaultStore<MainState, MainAction, MainEvent>(
    initialState = MainState.Initial,
    coroutineScope = coroutineScope,
) {
    override val middlewares: List<Middleware<MainState, MainAction, MainEvent>> = listOf(
        object : Middleware<MainState, MainAction, MainEvent> {
            override fun onActionProcessed(state: MainState, action: MainAction, nextState: MainState) {
                println("Action: $action .. $state")
            }

            override fun onEventEmitted(state: MainState, event: MainEvent) {
                println("Event: $event .. $state")
            }

            override fun onStateChanged(state: MainState, prevState: MainState) {
                println("State updated: $state")
            }

            override fun onStateEntered(state: MainState) {
                println("Enter: $state")
            }

            override fun onStateExited(state: MainState) {
                println("Exit: $state")
            }
        },
    )

    init {
        // 本当は Activity の onCreate() とかでやった方がよさそう
        start()
    }

    override suspend fun onEntered(state: MainState, emit: EventEmit<MainEvent>): MainState = when (state) {
        MainState.Initial -> {
            // すぐさま Loading に
            MainState.Loading
        }

        MainState.Loading -> {
            // UseCase や Repository からデータ取得
            delay(5_000)
            // データを読み終わったら Stable に
            MainState.Stable(listOf())
        }

        else -> null
    } ?: state

    override suspend fun onExited(state: MainState, emit: EventEmit<MainEvent>) {
    }

    override suspend fun onDispatched(state: MainState, action: MainAction, emit: EventEmit<MainEvent>): MainState = when (state) {
        is MainState.Stable -> when (action) { // Compose で state.dataList のデータを画面へ描画する
            is MainAction.Click -> {
                // イベント発行例
                emit(MainEvent.ShowToast("クリクされました"))
                // state の更新は data class の copy で
                state.copy(clickCounter = state.clickCounter + 1)
            }
        }

        else -> null
    } ?: state
}

// MainStore 等を状況に応じてモックできるようにする
interface Store<S : State, A : Action, E : Event> {
    val state: StateFlow<S>

    val event: Flow<E>

    val currentState: S

    fun start()

    fun dispatch(action: A)

    fun collect(onState: OnState<S>, onEvent: OnEvent<E>): Job

    fun interface OnState<S> {
        suspend operator fun invoke(event: S)
    }

    fun interface OnEvent<E> {
        suspend operator fun invoke(event: E)
    }
}

abstract class DefaultStore<S : State, A : Action, E : Event>(
    private val initialState: S,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()), // CoroutineExceptionHandlerは自前で挟んでもらう
) : Store<S, A, E> {
    private val _state: MutableStateFlow<S> = MutableStateFlow(initialState)
    override val state: StateFlow<S> = _state // Compose で購読

    private val _event: MutableSharedFlow<E> = MutableSharedFlow()
    override val event: Flow<E> = _event // Compose で購読

    override val currentState: S get() = _state.value

    protected open val middlewares: List<Middleware<S, A, E>> = emptyList()

    private val mutex = Mutex()

    override fun start() {
        if (_state.value != initialState) return
        coroutineScope.launch {
            mutex.withLock {
                processState()
            }
        }
    }

    override fun dispatch(action: A) { // ユーザによる操作. Compose の画面から叩く
        coroutineScope.launch {
            mutex.withLock {
                processState(action)
            }
        }
    }

    override fun collect(onState: Store.OnState<S>, onEvent: Store.OnEvent<E>): Job {
        return coroutineScope.launch {
            launch { state.collect { onState(it) } }
            launch { event.collect { onEvent(it) } }
        }
    }

    protected open suspend fun onEntered(state: S, emit: EventEmit<E>): S = state

    protected open suspend fun onExited(state: S, emit: EventEmit<E>) {}

    protected open suspend fun onDispatched(state: S, action: A, emit: EventEmit<E>): S = state

    // viseModelScope のような auto close の CoroutinesScope 以外の場合に利用
    protected fun dispose() {
        coroutineScope.cancel()
    }

    private suspend fun processState(action: A? = null) {
        val currentState = _state.value

        val nextState = action?.run {
            onDispatched(currentState, action, ::processEvent).apply {
                processActonMiddleware(currentState, action, this)
            }
        } ?: run {
            processEnterMiddleware(currentState)
            onEntered(currentState, ::processEvent)
        }

        if (currentState::class.qualifiedName != nextState::class.qualifiedName) {
            onExited(currentState, ::processEvent)
            processExitMiddleware(currentState)
        }

        _state.update { nextState }

        if (currentState != nextState) {
            processStateMiddleware(nextState, currentState)
        }

        if (currentState::class.qualifiedName != nextState::class.qualifiedName) {
            processState()
        }
    }

    private suspend fun processEvent(event: E) {
        _event.emit(event)
        processEventMiddleware(_state.value, event)
    }

    private suspend fun processActonMiddleware(state: S, action: A, nextState: S) {
        middlewares.forEach {
            it.onActionProcessed(state, action, nextState)
            it.onActionProcessedSuspend(state, action, nextState)
        }
    }

    private suspend fun processEventMiddleware(state: S, event: E) {
        middlewares.forEach {
            it.onEventEmitted(state, event)
            it.onEventEmittedSuspend(state, event)
        }
    }

    private suspend fun processEnterMiddleware(state: S) {
        middlewares.forEach {
            it.onStateEntered(state)
            it.onStateEnteredSuspend(state)
        }
    }

    private suspend fun processExitMiddleware(state: S) {
        middlewares.forEach {
            it.onStateExited(state)
            it.onStateExitedSuspend(state)
        }
    }

    private suspend fun processStateMiddleware(state: S, prevState: S) {
        middlewares.forEach {
            it.onStateChanged(state, prevState)
            it.onStateChangedSuspend(state, prevState)
        }
    }

    protected fun interface EventEmit<E> {
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
    data class Click(val id: Long) : MainAction
}

sealed interface MainEvent : Event {
    data class ShowToast(val message: String) : MainEvent
}

interface Middleware<S : State, A : Action, E : Event> {
    fun onActionProcessed(state: S, action: A, nextState: S) {}
    suspend fun onActionProcessedSuspend(state: S, action: A, nextState: S) {}
    fun onEventEmitted(state: S, event: E) {}
    suspend fun onEventEmittedSuspend(state: S, event: E) {}
    fun onStateEntered(state: S) {}
    suspend fun onStateEnteredSuspend(state: S) {}
    fun onStateExited(state: S) {}
    suspend fun onStateExitedSuspend(state: S) {}
    fun onStateChanged(state: S, prevState: S) {}
    suspend fun onStateChangedSuspend(state: S, prevState: S) {}
}
