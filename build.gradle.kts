import java.time.Duration
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.2.0"
    id("org.graalvm.buildtools.native") version "1.1.19"
    id("com.diffplug.spotless") version "8.11.0"
}

group = "com.encircle360.oss"
version = "0.2.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// The integration tests run Bankbote against real servers in containers. They have their own source
// set and tasks, outside the coverage gate of the unit tests, and reuse the fake servers of the unit
// tests.
val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    // The Spring Boot BOM pins every version below that carries none.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "integrationTestImplementation"("org.testcontainers:testcontainers")
    "integrationTestImplementation"("org.testcontainers:testcontainers-junit-jupiter")
    "integrationTestRuntimeOnly"("org.mariadb.jdbc:mariadb-java-client")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.add("-parameters")
}

// Every warning of the own sources is an error; the sources Spring generates for the native image
// are compiled without that rule, as their raw types are not ours to fix.
listOf("compileJava", "compileTestJava", "compileIntegrationTestJava").forEach { name ->
    tasks.named<JavaCompile>(name) {
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial", "-Werror"))
    }
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

val coverageExclusions = listOf("com/encircle360/oss/bankbote/BankboteApplication*")

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

// Every container image of the integration tests, the helper of Testcontainers included, is pinned by
// tag and digest in this one file, which Dependabot keeps up to date. The tests read it; a variable
// BANKBOTE_IMAGE_<NAME> overrides one image, such as a locally built Hibiscus Server.
val integrationImages = file("src/integrationTest/images/Dockerfile")

fun pinnedImage(name: String): String =
    integrationImages.readLines()
        .map { it.trim() }
        .filter { it.startsWith("FROM ") }
        .map { it.removePrefix("FROM ").substringBefore(" ") }
        .single { it.substringBefore("@").substringBeforeLast(":").endsWith("/$name") || it.startsWith("$name:") }

fun registerIntegrationTests(name: String, tag: String, text: String, limit: Duration) =
    tasks.register<Test>(name) {
        // A stuck container turns the task red at this limit instead of holding the build.
        timeout = limit
        description = text
        group = "verification"
        testClassesDirs = integrationTest.output.classesDirs
        classpath = integrationTest.runtimeClasspath
        useJUnitPlatform { includeTags(tag) }
        shouldRunAfter(tasks.test)
        inputs.file(integrationImages)
        systemProperty("bankbote.images", integrationImages.absolutePath)
        environment("TESTCONTAINERS_RYUK_CONTAINER_IMAGE", pinnedImage("ryuk"))
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

registerIntegrationTests(
    "hibiscusTest",
    "hibiscus",
    "Runs Bankbote against a real Hibiscus Server with MariaDB in containers.",
    Duration.ofMinutes(10),
)

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

registerIntegrationTests(
    "erpnextTest",
    "erpnext",
    "Runs Bankbote against a real ERPNext 16 with MariaDB and Redis in containers.",
    Duration.ofMinutes(20),
)
