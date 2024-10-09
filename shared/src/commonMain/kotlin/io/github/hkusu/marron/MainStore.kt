package io.github.hkusu.marron

import io.github.hkusu.marron.tarte.Action
import io.github.hkusu.marron.tarte.BaseStore
import io.github.hkusu.marron.tarte.Event
import io.github.hkusu.marron.tarte.Middleware
import io.github.hkusu.marron.tarte.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

// ViewModel で保持（ KMP プロジェクトでない場合は ViewModel に Store を implement してもよし
class MainStore(
    // UseCase や Repository を inject
    //   getHogeListUseCase: GetHogeListUseCase,
    //   setgFugaUseCase: SetFugaUseCase,
    coroutineScope: CoroutineScope, // 基本は viewModelScope を渡す想定
    // 外から Middleware を渡す場合
    //    override val middlewares: List<Middleware<MainState, MainAction, MainEvent>>
) : BaseStore<MainState, MainAction, MainEvent>(
    initialState = MainState.Welcome,
    coroutineScope = coroutineScope,
) {
    override val middlewares: List<Middleware<MainState, MainAction, MainEvent>> = listOf(
        object : Middleware<MainState, MainAction, MainEvent> {
            override suspend fun runAfterActionDispatch(state: MainState, action: MainAction, nextState: MainState) {
                println("Action: $action .. $state")
            }

            override suspend fun runAfterEventEmit(state: MainState, event: MainEvent) {
                println("Event: $event .. $state")
            }

            override suspend fun runAfterStateChange(state: MainState, prevState: MainState) {
                println("State updated: $state")
            }

            override suspend fun runAfterStateEnter(state: MainState, nextState: MainState) {
                println("Enter: $state")
            }

            override suspend fun runAfterStateExit(state: MainState) {
                println("Exit: $state")
            }

            override suspend fun runAfterError(state: MainState, nextState: MainState, throwable: Throwable) {
                println("Error: $throwable")
            }
        },
    )

    override suspend fun onEnter(state: MainState, emit: EventEmit<MainEvent>): MainState = when (state) {
        MainState.Welcome -> {
            delay(2_000)
            // ２病後に Loading に
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

    override suspend fun onDispatch(state: MainState, action: MainAction, emit: EventEmit<MainEvent>): MainState = when (state) {
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

    override suspend fun onError(state: MainState, throwable: Throwable, emit: EventEmit<MainEvent>): MainState = MainState.Error
}

sealed interface MainState : State {
    data object Welcome : MainState
    data object Loading : MainState
    data class Stable(
        val dataList: List<String>,
        val clickCounter: Int = 0,
    ) : MainState

    data object Error : MainState
}

sealed interface MainAction : Action {
    data class Click(val id: Long) : MainAction
}

sealed interface MainEvent : Event {
    data class ShowToast(val message: String) : MainEvent
}
