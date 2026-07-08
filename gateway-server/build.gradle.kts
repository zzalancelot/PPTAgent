plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val springAiVersion = rootProject.extra["springAiVersion"] as String
val grpcVersion = "1.68.1"

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:$springAiVersion")
    }
}

dependencies {
    implementation(project(":gateway-api"))
    implementation(project(":framework"))
    implementation(project(":config"))

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // OpenAI-compatible client library (no auto-config). DeepSeek, MiMo and
    // MiniMax are all reached through OpenAI-compatible endpoints; concrete
    // ChatModel instances are built manually per gateway.models entry.
    implementation("org.springframework.ai:spring-ai-openai")

    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("io.grpc:grpc-inprocess:$grpcVersion")
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

// Run from the repo root so `spring.config.import: optional:file:./ai-keys.yaml`
// resolves the root-level (gitignored) key file.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
