import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.20"
    application
}

group = "graphics.scenery"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.scijava.org/content/groups/public")
    maven("https://jitpack.io")
}

dependencies {
    api("graphics.scenery:scenery:222a631")
    api("org.slf4j:slf4j-simple:1.7.36")
    implementation("org.joml:joml:1.10.4")
    implementation("net.imglib2:imglib2:5.13.0")
    implementation("com.github.scenerygraphics:trx-jvm:73070ac")


    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

application {
    mainClass.set("MainKt")
}