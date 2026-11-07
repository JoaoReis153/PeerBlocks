plugins {
    application
    id("com.diffplug.spotless") version "8.10.4"
}

repositories {
    mavenCentral() // Spotless downloads the formatter from here
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.38")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat().aosp()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// Demo and benchmark launchers. They use the app but are not shipped with it.
val tools: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[tools.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[tools.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

// Make sure the launchers still compile whenever the project is checked (and in CI)
tasks.check {
    dependsOn(tools.classesTaskName)
}

tasks.test {
    useJUnitPlatform()
    workingDir = rootDir // nodes use the relative path nodes/node-{id}
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

application {
    mainClass = "dev.joaoreis.peerblocks.App"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootDir
}

// ./gradlew demo --args="1 2 3": opens connected nodes, the first shares sample files
tasks.register<JavaExec>("demo") {
    group = "application"
    description = "Opens a window per node id, all connected to each other"
    classpath = tools.runtimeClasspath
    mainClass = "dev.joaoreis.peerblocks.tools.Demo"
    workingDir = rootDir
}

// ./gradlew benchmark --args="[maxSeeds] [iterations] [fileSizeBytes] [outputCsv]"
tasks.register<JavaExec>("benchmark") {
    group = "application"
    description = "Measures download speed as the number of seeders grows"
    classpath = tools.runtimeClasspath
    mainClass = "dev.joaoreis.peerblocks.tools.Benchmark"
    workingDir = rootDir
}
