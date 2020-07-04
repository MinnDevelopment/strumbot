import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.github.johnrengelman.shadow") version "5.1.0"
    id("org.jetbrains.kotlin.jvm") version "1.3.70"
    application
}

application {
    mainClassName = "strumbot.Main"
}

group = "dev.minn"
version = "1.0.0"

repositories {
    jcenter()
    maven("https://oss.jfrog.org/artifactory/libs-release")
    maven("https://jitpack.io")
}

dependencies {
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("net.dv8tion:JDA:4.2.0_+")
    implementation("club.minnced:jda-reactor:1.2.0")
    implementation("club.minnced:discord-webhooks:0.3.1")
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.3.2")
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
