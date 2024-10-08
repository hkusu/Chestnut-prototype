package io.github.hkusu.marron.tarte

interface Middleware<S : State, A : Action, E : Event> {
    suspend fun runBeforeActionDispatch(state: S, action: A) {}
    suspend fun runAfterActionDispatch(state: S, action: A, nextState: S) {}
    suspend fun runBeforeEventEmit(state: S, event: E) {}
    suspend fun runAfterEventEmit(state: S, event: E) {}
    suspend fun runBeforeStateEnter(state: S) {}
    suspend fun runAfterStateEnter(state: S, nextState: S) {}
    suspend fun runBeforeStateExit(state: S) {}
    suspend fun runAfterStateExit(state: S) {}
    suspend fun runBeforeStateChange(state: S, nextState: S) {}
    suspend fun runAfterStateChange(state: S, prevState: S) {}
    suspend fun runBeforeErrorHandle(state: S, throwable: Throwable) {}
    suspend fun runAfterErrorHandle(state: S, nextState: S, throwable: Throwable) {}
}
