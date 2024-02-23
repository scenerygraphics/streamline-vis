import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.22" //used to use 1.7.20
    application
    `maven-publish`
}

group = "graphics.scenery"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.scijava.org/content/groups/public")
    maven("https://jitpack.io")
    //manages local dependencies that are used in this project
    mavenLocal()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "graphics.scenery"
            artifactId = "streamline-vis"
            version = "1.0-SNAPSHOT"

            from(components["java"])
        }
    }
}

dependencies {
    api("graphics.scenery:scenery:0.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    //branch with new Curve implementation, that offers better curve metrics and will be used in future
    //implementation("com.github.scenerygraphics:scenery:curve_restructuring-SNAPSHOT")
    api("org.slf4j:slf4j-simple:2.0.9")
    api("org.yaml:snakeyaml:1.33") {
        version { strictly("1.33") }
    }
    implementation("org.joml:joml:1.10.4")
    implementation("net.imglib2:imglib2:5.13.0")
    //used for the mesh implementation that is currently used for the point-test
    implementation("com.github.imglib:imglib2-mesh:e7d4e89")
    api("graphics.scenery:trx-jvm:47d1732")
    implementation("com.opencsv:opencsv:5.4") //used for reading .csv
    // https://mvnrepository.com/artifact/net.imglib2/imglib2-ij
    //implementation("net.imglib2:imglib2-ij:2.0.0-beta-46")

    implementation(platform("org.scijava:pom-scijava:37.0.0"))
    implementation("io.scif:scifio")
    runtimeOnly("io.scif:scifio-bf-compat")
    runtimeOnly("ome:formats-bsd:7.1.0") {
        exclude("org.slf4j", "slf4j-api")
        exclude("org.slf4j", "slf4j-simple")
    }
    runtimeOnly("ome:formats-gpl:7.1.0") {
        exclude("org.slf4j", "slf4j-api")
        exclude("org.slf4j", "slf4j-simple")
    }

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "21"
}

tasks.withType<JavaExec> {
    if(javaVersion >= JavaVersion.VERSION_17) {
        allJvmArgs = allJvmArgs + listOf("--add-opens=java.base/java.nio=ALL-UNNAMED",
                                         "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED")
    }
}

application {
    mainClass.set("Streamlines")
}
