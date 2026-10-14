pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven { setUrl("https://jitpack.io") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "echo-pc-poc"
include(":innertube", ":metadata", ":lrclib", ":kugou", ":betterlyrics", ":simpmusic", ":youlyplus", ":librarydb", ":app")



