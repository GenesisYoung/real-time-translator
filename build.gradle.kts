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
    // Runs Silero VAD on the CPU. The jar bundles native libraries for Windows, Linux and macOS.
    // Keep the minor version equal to the ONNX Runtime inside sherpa-onnx (TTS; 1.13.8 ships 1.28.2):
    // Windows loads only one onnxruntime.dll per process, and both libraries then share it.
    implementation("com.microsoft.onnxruntime:onnxruntime:1.28.0")
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
    // Built by hand (see native/stt/README.md); the Whisper binding tests are skipped until it exists.
    val os = System.getProperty("os.name")
    systemProperty("vct.stt.lib", file(when {
        os.startsWith("Windows") -> "native/stt/build/Release/vct_stt.dll"
        os.startsWith("Mac") -> "native/stt/build/vct_stt.dylib"
        else -> "native/stt/build/vct_stt.so"
    }).path)
    // Optional: a sherpa-onnx library and voice folder for the TTS binding tests, e.g.
    //   gradlew test -Pvct.tts.lib=native/sherpa-onnx/sherpa-onnx-c-api.dll -Pvct.tts.testVoice=models/tts/<voice>
    listOf("vct.tts.lib", "vct.tts.testVoice").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, file(it).path) }
    }
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

// Checks the speech engines on this PC (see docs/speech-setup.md). Examples:
//   gradlew speechCheck --args="transcribe build\capture-check\remote.wav"
//   gradlew speechCheck --args="say 我们应该往左走。"
//   gradlew speechCheck --args="live --openai-model <model> --remote others"
tasks.register<JavaExec>("speechCheck") {
    group = "verification"
    description = "Checks Whisper, the TTS voice, or the whole live pipeline."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.vct.tools.SpeechCheck"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("vct.audio.lib", file("native/build/Release/vct_audio.dll").path)
    systemProperty("file.encoding", "UTF-8")
    systemProperty("stdout.encoding", "UTF-8")
    standardInput = System.`in`
}
