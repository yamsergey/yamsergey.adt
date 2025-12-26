plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf.gradle)
    application
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    // Kotlin
    implementation(libs.kotlinx.coroutines.core)

    // gRPC and Protobuf
    implementation(libs.grpc.netty)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.kotlin)

    // Android tools
    implementation(libs.ddmlib)

    // CLI
    implementation(libs.picocli)

    // Utils
    implementation(libs.guava)

    // JSON serialization
    implementation(libs.jackson.databind)
    implementation(libs.jackson.core)

    // Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.28.3"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.68.1"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
                create("grpckt")
            }
            task.builtins {
                create("kotlin")
            }
        }
    }
}

application {
    mainClass.set("io.yamsergey.adt.layoutinspector.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

// Distribution configuration
tasks.named<CreateStartScripts>("startScripts") {
    applicationName = "layout-inspector-cli"
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "io.yamsergey.adt.layoutinspector.cli.MainKt"
    }
}
