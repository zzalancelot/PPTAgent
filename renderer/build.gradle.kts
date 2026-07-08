plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    // Kotlin file RenderPptxMain.kt with a top-level main() compiles to this class.
    mainClass = "com.ppt.agent.renderer.cli.RenderPptxMainKt"
}

dependencies {
    // Renderer is a standalone agent tool: framework (for the Json helper) + POI only.
    // Must NOT depend on business / app / llm-adapter / gateway-*.
    implementation(project(":framework"))
    implementation("org.apache.poi:poi-ooxml:5.4.1")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Run from the repo root so relative paths like `docs/...` in --args resolve,
// matching the documented `./gradlew :renderer:run --args="--input docs/..."`.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
