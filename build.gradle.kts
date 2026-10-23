plugins {
    java
    jacoco
    checkstyle
    id("org.springframework.boot") version "3.5.11"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.sonarqube") version "7.4.0.8496"
    id("org.cyclonedx.bom") version "2.2.0"
}

group = "com.eudistack"
// Plain version, no SNAPSHOT: the release job tags v${version}.
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

jacoco {
    toolVersion = "0.8.14"
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("software.amazon.awssdk:bom:2.31.78")
    }
}

dependencies {
    // --- Web (servlet, AD-1) ---
    // Spring MVC on virtual threads, not WebFlux: the batch is one JDBC transaction with a
    // search_path change per event; R2DBC would only re-express it (technical-design AD-1).
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // --- Persistence: JDBC + HikariCP, no JPA (decision D-3 in docs/architecture.md) ---
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // --- Catalogue (YAML snapshots on the classpath) ---
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")

    // --- Bearer tokens issued by the Verifier (JWKS) ---
    implementation("com.nimbusds:nimbus-jose-jwt:10.9.1")

    // --- Cold storage (S3 Object Lock, AD-7) ---
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:apache-client")

    // --- Observability (OTLP native, no java agent — SAD §8.9) ---
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.micrometer:micrometer-registry-otlp")
    // Logback → OTLP, as the Verifier: pinned to the release paired with the opentelemetry-api that
    // Boot 3.5.11 manages (the instrumentation BOM would override it).
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.15.0-alpha")

    // --- DevTools ---
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // --- Testing ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:localstack")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    testImplementation("com.networknt:json-schema-validator:3.0.8")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<org.cyclonedx.gradle.CycloneDxTask>("cyclonedxBom") {
    includeConfigs.set(listOf("runtimeClasspath"))
    schemaVersion.set("1.6")
    destination.set(layout.buildDirectory.dir("reports/sbom").get().asFile)
    outputName.set("sbom")
    outputFormat.set("json")
    includeBomSerialNumber.set(false)
    includeLicenseText.set(false)
}

sonar {
    properties {
        property("sonar.projectKey", "in2workspace_eudistack-core-audit")
        property("sonar.organization", "in2workspace")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.sources", "src/main/java,src/main/resources")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/jacoco/test/jacocoTestReport.xml")
        property("sonar.coverage.exclusions", "src/main/java/**/AuditApplication.java")
    }
}
checkstyle {
    configFile = file("${rootDir}/config/checkstyle/checkstyle.xml")
}

// Spring Boot builds both the executable jar and a "-plain" one; the image copies
// build/libs/*.jar, so the plain jar is switched off to keep that COPY unambiguous.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

// Unit tests (*Test) run without Docker. Integration tests (*IT) need Testcontainers
// (PostgreSQL) and run in their own task, which `check` also runs.
tasks.named<Test>("test") {
    useJUnitPlatform()
    exclude("**/*IT.class")
    finalizedBy(tasks.jacocoTestReport)
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs the Testcontainers integration tests (*IT)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("**/*IT.class")
    shouldRunAfter(tasks.named("test"))
}

tasks.named("check") {
    dependsOn(integrationTest)
}

// LOCAL DEVELOPMENT ONLY: signs access tokens with a local key and writes the env file that
// compose.yaml reads, so the stack can be tried by hand without the Verifier (README "Probar en local").
tasks.register<JavaExec>("devTokens") {
    description = "Writes local access tokens and their JWKS into dev-tools/local-tokens."
    group = "local development"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.eudistack.audit.devtools.DevTokens"
    args(layout.projectDirectory.dir("dev-tools/local-tokens").asFile.absolutePath)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        csv.required = false
    }
}
