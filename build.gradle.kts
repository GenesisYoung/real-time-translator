plugins {
    java
}

group = "com.vct"
version = "0.1.0-phase1"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    // Built and tested on JDK 23. Runs unchanged on JDK 25 LTS (recommended on Windows).
    // FFM (java.lang.foreign), used by the upcoming WASAPI binding, is final since JDK 22.
    sourceCompatibility = JavaVersion.VERSION_23
    targetCompatibility = JavaVersion.VERSION_23
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 23
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
