import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.3.61"
    application
}

application {
    mainClassName = "strumbot.Main"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    jcenter()
    maven("https://oss.jfrog.org/artifactory/libs-release")
}

val glitchVersion = "0.6.1"

dependencies {
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("net.dv8tion:JDA:4.0.0_72")
    implementation("club.minnced:jda-reactor:1.0.0")
    implementation("club.minnced:discord-webhooks:0.1.8")
    implementation(kotlin("stdlib-jdk8"))
}

val compileKotlin: KotlinCompile by tasks
val compileTestKotlin by tasks

compileKotlin.apply {
    kotlinOptions.jvmTarget = "1.8"
}