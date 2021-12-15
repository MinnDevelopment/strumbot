import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.github.johnrengelman.shadow") version "5.1.0"
    kotlin("jvm") version "1.6.0"
    application
}

application {
    mainClassName = "strumbot.Main"
}

group = "dev.minn"
version = "1.2.0"

repositories {
    mavenLocal() // caching optimization
    mavenCentral() // everything else
    maven("https://m2.dv8tion.net/releases") // jda
    maven("https://jitpack.io") // jda-reactor and slash commands
}

dependencies {
    implementation("ch.qos.logback:logback-classic:1.2.8")
    implementation("net.dv8tion:JDA:4.+") {
        exclude(module="opus-java")
    }
    implementation("com.github.minndevelopment:jda-ktx:34b55c0")
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0-RC2")
}

val clean by tasks
val build by tasks
val compileKotlin: KotlinCompile by tasks
val shadowJar: ShadowJar by tasks

compileKotlin.apply {
    kotlinOptions.jvmTarget = "11"
}

tasks.create<Copy>("install") {
    shadowJar.mustRunAfter(clean)
    dependsOn(shadowJar)
    dependsOn(clean)

    from(shadowJar.archiveFile.get())
    from("src/scripts")
    from("example-config.json")
    val output = "$buildDir/install/strumbot"
    into("$output/")
    doFirst { File(output).delete() }
    doLast {
        setupScript("$output/run.bat")
        setupScript("$output/run.sh")
        setupScript("$output/docker-setup.sh")
        setupScript("$output/docker-setup.bat")
        val archive = File("$output/${shadowJar.archiveFileName.get()}")
        archive.renameTo(File("$output/strumbot.jar"))
        val config = File("$output/example-config.json")
        config.renameTo(File("$output/config.json"))
    }
}

build.dependsOn(shadowJar)

fun setupScript(path: String) {
    val file = File(path)
    file.writeText(file.readText()
        .replace("%NAME%", "strumbot.jar")
        .replace("%VERSION%", version.toString()))
    file.setExecutable(true)
    file.setReadable(true)
}
