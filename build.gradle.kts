import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.0"
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
    api("graphics.scenery:scenery:ec17ef59")
    api("sc.fiji:bigdataviewer-core:10.4.0")
    //api(files("./libs/sis-base-18.09.0.jar"))
    //api(files("./libs/sis-jhdf5-1654327451.jar"))
    api("org.slf4j:slf4j-simple:1.7.36")
    //api("org.zeromq:jeromq:0.5.2")
    api("org.msgpack:jackson-dataformat-msgpack:0.9.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.3")
    implementation("org.joml:joml:1.10.4")
    implementation("net.imglib2:imglib2:5.13.0")
    implementation("com.github.scenerygraphics:trx-jvm:7ec9d07")


    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClass.set("MainKt")
}