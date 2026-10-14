plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
}
kotlin { jvmToolchain(21) }

val room = "2.8.5"
dependencies {
    api("androidx.room:room-runtime:$room")
    api("androidx.sqlite:sqlite-bundled:2.6.2")
    ksp("androidx.room:room-compiler:$room")
    api(project(":innertube"))
    api("org.jetbrains.compose.runtime:runtime:1.12.1") // @Immutable on the entities
    api("org.apache.commons:commons-lang3:3.20.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
