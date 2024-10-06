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
            override suspend fun afterActionDispatch(state: MainState, action: MainAction, nextState: MainState) {
                println("Action: $action .. $state")
            }

            override suspend fun afterEventEmit(state: MainState, event: MainEvent) {
                println("Event: $event .. $state")
            }

            override suspend fun afterStateChange(state: MainState, prevState: MainState) {
                println("State updated: $state")
            }

            override suspend fun afterStateEnter(state: MainState, nextState: MainState) {
                println("Enter: $state")
            }

            override suspend fun afterStateExit(state: MainState) {
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
                processState(_state.value)
            }
        }
    }

    override fun dispatch(action: A) { // ユーザによる操作. Compose の画面から叩く
        coroutineScope.launch {
            mutex.withLock {
                processState(_state.value, action)
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

    private suspend fun processState(state: S, action: A? = null) {
        val nextState = action?.run {
            processActonDispatchMiddleware(state, action) {
                onDispatched(state, action) { processEvent(state, it) }
            }
        } ?: run {
            processStateEnterMiddleware(state) {
                onEntered(state) { processEvent(state, it) }
            }
        }

        if (state::class.qualifiedName != nextState::class.qualifiedName) {
            processStateExitMiddleware(state) {
                onExited(state) { processEvent(state, it) }
            }
        }

        if (state != nextState) {
            processStateChangeMiddleware(nextState, state) {
                _state.update { nextState }
            }
        }

        if (state::class.qualifiedName != nextState::class.qualifiedName) {
            processState(nextState)
        }
    }

    private suspend fun processActonDispatchMiddleware(state: S, action: A, block: suspend () -> S): S {
        middlewares.forEach {
            it.beforeActionDispatch(state, action)
        }
        val nextState = block.invoke()
        middlewares.forEach {
            it.afterActionDispatch(state, action, nextState)
        }
        return nextState
    }

    private suspend fun processEventEmitMiddleware(state: S, event: E, block: suspend () -> Unit) {
        middlewares.forEach {
            it.beforeEventEmit(state, event)
        }
        block.invoke()
        middlewares.forEach {
            it.afterEventEmit(state, event)
        }
    }

    private suspend fun processStateEnterMiddleware(state: S, block: suspend () -> S): S {
        middlewares.forEach {
            it.beforeStateEnter(state)
        }
        val nextState = block.invoke()
        middlewares.forEach {
            it.afterStateEnter(state, nextState)
        }
        return nextState
    }

    private suspend fun processStateExitMiddleware(state: S, block: suspend () -> Unit) {
        middlewares.forEach {
            it.beforeStateExit(state)
        }
        block.invoke()
        middlewares.forEach {
            it.afterStateExit(state)
        }
    }

    private suspend fun processStateChangeMiddleware(state: S, nextState: S, block: () -> Unit) {
        middlewares.forEach {
            it.beforeStateChange(state, nextState)
        }
        block.invoke()
        middlewares.forEach {
            it.afterStateChange(nextState, state)
        }
    }

    private suspend fun processEvent(state: S, event: E) {
        processEventEmitMiddleware(state, event) {
            _event.emit(event)
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
    suspend fun beforeActionDispatch(state: S, action: A) {}
    suspend fun afterActionDispatch(state: S, action: A, nextState: S) {}
    suspend fun beforeEventEmit(state: S, event: E) {}
    suspend fun afterEventEmit(state: S, event: E) {}
    suspend fun beforeStateEnter(state: S) {}
    suspend fun afterStateEnter(state: S, nextState: S) {}
    suspend fun beforeStateExit(state: S) {}
    suspend fun afterStateExit(state: S) {}
    suspend fun beforeStateChange(state: S, nextState: S) {}
    suspend fun afterStateChange(state: S, prevState: S) {}
}
