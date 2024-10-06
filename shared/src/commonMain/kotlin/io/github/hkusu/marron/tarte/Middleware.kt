package io.github.hkusu.marron.tarte

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
