import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.github.johnrengelman.shadow") version "5.1.0"
    id("org.jetbrains.kotlin.jvm") version "1.3.61"
    application
}

application {
    mainClassName = "strumbot.Main"
}

group = "dev.minn"
version = "0.1.6"

repositories {
    jcenter()
    maven("https://oss.jfrog.org/artifactory/libs-release")
}

dependencies {
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("net.dv8tion:JDA:4.1.0_97")
    implementation("club.minnced:jda-reactor:1.0.0")
    implementation("club.minnced:discord-webhooks:0.2.0")
    implementation(kotlin("stdlib-jdk8"))
}

val clean by tasks
val build by tasks
val compileKotlin: KotlinCompile by tasks
val shadowJar: ShadowJar by tasks

compileKotlin.apply {
    kotlinOptions.jvmTarget = "1.8"
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
