import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.20"
    application
    `maven-publish`
}

group = "graphics.scenery"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.scijava.org/content/groups/public")
    maven("https://jitpack.io")
}

dependencies {
    api("graphics.scenery:scenery:6f505c4c")
    api("org.slf4j:slf4j-simple:1.7.36")
    implementation("org.joml:joml:1.10.4")
    implementation("net.imglib2:imglib2:5.13.0")
    api("graphics.scenery:trx-jvm:47d1732")
    //just for test purposes - can only be used if the first line of this block is outcommented; for testing the curve_restructuring branch of Scenery
    //implementation("com.github.scenerygraphics:scenery:curve_restructuring-SNAPSHOT")

    implementation(platform("org.scijava:pom-scijava:32.0.0"))
    implementation("io.scif:scifio")
    runtimeOnly("io.scif:scifio-bf-compat")
    runtimeOnly("ome:formats-bsd")
    runtimeOnly("ome:formats-gpl")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

application {
    mainClass.set("BasicStreamlineExample")
}
