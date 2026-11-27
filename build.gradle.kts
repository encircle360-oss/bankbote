import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.1.3"
    id("org.graalvm.buildtools.native") version "1.1.19"
    id("com.diffplug.spotless") version "8.11.0"
}

group = "com.encircle360.bankbote"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // The Spring Boot BOM pins every version below that carries none.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial", "-Werror", "-parameters"))
}

tasks.test {
    useJUnitPlatform()
    // The tests write temporary key stores with keytool and start local HTTP servers; nothing leaves the machine.
    systemProperty("java.net.preferIPv4Stack", "true")
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.16"
}

val coverageExclusions = listOf("com/encircle360/bankbote/BankboteApplication*")

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
    classDirectories.setFrom(files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } }))
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } }))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "1.00".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "1.00".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat("2.0.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        trimTrailingWhitespace()
        endWithNewline()
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName = "bankbote"
            // Everything but glibc is linked into the binary, which the distroless base provides.
            // The charsets cover what a Hibiscus Server may name in its content type.
            buildArgs.addAll(
                listOf(
                    "--no-fallback",
                    "--static-nolibc",
                    "--enable-url-protocols=http,https",
                    "-H:+AddAllCharsets",
                    "-H:+ReportExceptionStackTraces",
                ),
            )
        }
    }
    // The metadata repository holds reachability metadata for third-party libraries; Spring's AOT processing
    // covers the application itself.
    metadataRepository {
        enabled = true
    }
}

// The tests run on the JVM against local fake servers whose addresses exist only while a test runs,
// so their application contexts cannot be prepared ahead of time; the native image is built from
// the main sources alone.
tasks.named("processTestAot") {
    enabled = false
}
