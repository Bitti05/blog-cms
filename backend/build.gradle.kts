plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    java
}

group = "io.atomity"
version = "0.1.0-SNAPSHOT"

val targetJavaVersion = if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
    JavaVersion.current().majorVersion.toInt()
} else {
    21
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.security)
    // Brings Nimbus JOSE: signs and verifies the admin session JWT (HS256).
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.micrometer.prometheus)

    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${libs.versions.testcontainers.get()}")
    }
}

// The plain (non-boot) jar is never deployed; skipping it leaves exactly one
// jar in build/libs so the Dockerfile's COPY is unambiguous.
tasks.jar {
    enabled = false
}

tasks.withType<Test> {
    useJUnitPlatform()
}
