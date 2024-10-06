package io.github.hkusu.marron.tarte

sealed interface Contract
interface State : Contract
interface Action : Contract
interface Event : Contract
