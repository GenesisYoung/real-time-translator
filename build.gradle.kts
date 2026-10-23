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

// Test double of the native audio library, so the FFM binding is tested on macOS/Linux too.
// Skipped on Windows and where no C compiler is installed; the binding test is then skipped.
val nativeStub = layout.buildDirectory.file("native-stub/" + System.mapLibraryName("vct_audio_stub"))
val buildNativeStub = tasks.register<Exec>("buildNativeStub") {
    description = "Builds native/stub, the test double of the vct_audio library."
    val source = file("native/stub/vct_audio_stub.c")
    inputs.files(source, file("native/include/vct_audio.h"))
    outputs.file(nativeStub)
    onlyIf {
        !System.getProperty("os.name").startsWith("Windows") &&
            System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, "cc").canExecute() }
    }
    doFirst { nativeStub.get().asFile.parentFile.mkdirs() }
    commandLine("cc", "-shared", "-fPIC", "-O2", "-I", file("native/include").path,
        "-o", nativeStub.get().asFile.path, source.path, "-lm")
}

tasks.test {
    useJUnitPlatform()
    dependsOn(buildNativeStub)
    systemProperty("vct.audio.stub", nativeStub.get().asFile.path)
    // FFM downcalls are "restricted methods"; without this the JDK prints a warning.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Records microphone and remote audio for a few seconds on Windows. Example:
//   gradlew captureCheck --args="--seconds 15 --remote discord"
tasks.register<JavaExec>("captureCheck") {
    group = "verification"
    description = "Checks WASAPI capture on this PC and writes mic.wav / remote.wav."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.vct.tools.CaptureCheck"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("vct.audio.lib", file("native/build/Release/vct_audio.dll").path)
    standardInput = System.`in`
}
