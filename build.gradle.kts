plugins {
    application
    id("com.diffplug.spotless") version "8.10.4"
}

repositories {
    mavenCentral() // Spotless downloads the formatter from here
}

dependencies {
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
        target("Code/src/**/*.java", "Code/test/**/*.java")
        googleJavaFormat().aosp()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

sourceSets.main {
    java.setSrcDirs(listOf("Code/src"))
}

sourceSets.test {
    java.setSrcDirs(listOf("Code/test"))
}

tasks.test {
    useJUnitPlatform()
    workingDir = rootDir // nodes use the relative path Code/files/dl{id}
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

application {
    mainClass = "Tests.Main"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootDir
}