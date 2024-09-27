package io.github.hkusu.marron

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform