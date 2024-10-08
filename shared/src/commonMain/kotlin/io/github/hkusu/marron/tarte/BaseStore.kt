package io.github.hkusu.marron.tarte

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

abstract class BaseStore<S : State, A : Action, E : Event>(
    private val initialState: S,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : Store<S, A, E> {
    private val _state: MutableStateFlow<S> = MutableStateFlow(initialState)
    override val state: StateFlow<S> by lazy {
        coroutineScope.launch {
            mutex.withLock {
                processAction(initialState)
            }
        }
        _state
    }

    private val _event: MutableSharedFlow<E> = MutableSharedFlow()
    override val event: Flow<E> = _event

    override val currentState: S get() = _state.value

    protected open val middlewares: List<Middleware<S, A, E>> = emptyList()

    private val mutex = Mutex()

    override fun dispatch(action: A) {
        state // initialize if need
        coroutineScope.launch {
            mutex.withLock {
                processAction(_state.value, action)
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

    protected fun dispose() {
        coroutineScope.cancel()
    }

    private suspend fun processAction(state: S, action: A? = null) {
        val nextState = action?.run {
            processActonDispatch(state, action)
        } ?: run {
            processStateEnter(state)
        }

        if (state::class != nextState::class) {
            processStateExit(state)
        }

        if (state != nextState) {
            processStateChange(state, nextState)
        }

        if (state::class != nextState::class) {
            processAction(nextState)
        }
    }

    private suspend fun processActonDispatch(state: S, action: A): S {
        middlewares.forEach {
            it.runBeforeActionDispatch(state, action)
        }
        val nextState = onDispatched(state, action) { processEventEmit(state, it) }
        middlewares.forEach {
            it.runAfterActionDispatch(state, action, nextState)
        }
        return nextState
    }

    private suspend fun processEventEmit(state: S, event: E) {
        middlewares.forEach {
            it.runBeforeEventEmit(state, event)
        }
        _event.emit(event)
        middlewares.forEach {
            it.runAfterEventEmit(state, event)
        }
    }

    private suspend fun processStateEnter(state: S): S {
        middlewares.forEach {
            it.runBeforeStateEnter(state)
        }
        val nextState = onEntered(state) { processEventEmit(state, it) }
        middlewares.forEach {
            it.runAfterStateEnter(state, nextState)
        }
        return nextState
    }

    private suspend fun processStateExit(state: S) {
        middlewares.forEach {
            it.runBeforeStateExit(state)
        }
        onExited(state) { processEventEmit(state, it) }
        middlewares.forEach {
            it.runAfterStateExit(state)
        }
    }

    private suspend fun processStateChange(state: S, nextState: S) {
        middlewares.forEach {
            it.runBeforeStateChange(state, nextState)
        }
        _state.update { nextState }
        middlewares.forEach {
            it.runAfterStateChange(nextState, state)
        }
    }

    protected fun interface EventEmit<E> {
        suspend operator fun invoke(event: E)
    }
}
