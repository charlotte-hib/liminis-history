import org.springframework.boot.gradle.plugin.SpringBootPlugin

// The model every app shares: JPA entities, repositories, small helpers, and the jobs that load a release and fill
// in bios from Wikipedia. A library, not an app.
plugins {
	`java-library`
	`java-test-fixtures`
	kotlin("jvm")
	kotlin("plugin.allopen")
	kotlin("plugin.spring")
	kotlin("plugin.jpa")
	kotlin("kapt")
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

val mapstructVersion = "1.6.3"

dependencies {
	// Gradle's own BOM support, not the dependency-management plugin: without the Spring Boot plugin to
	// align it, that plugin would pin the Kotlin compiler to the BOM's older Kotlin.
	implementation(platform(SpringBootPlugin.BOM_COORDINATES))
	// Entities and repositories are part of core's API: every app that uses them needs JPA.
	api("org.springframework.boot:spring-boot-starter-data-jpa")
	// The schema: SQL migrations in db/migration, which Flyway runs on Postgres in every app that uses core.
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")
	// The Wikipedia REST client that fills in bios and portraits (service/WikipediaClient).
	implementation("org.springframework.boot:spring-boot-starter-restclient")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.mapstruct:mapstruct:$mapstructVersion")
	kapt("org.mapstruct:mapstruct-processor:$mapstructVersion")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.flywaydb:flyway-core")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("com.github.victools:jsonschema-generator:5.0.0")
	testImplementation("com.networknt:json-schema-validator:3.0.8")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	// Postgres for the tests of every project, in a container: PostgresTestConfiguration.
	testFixturesImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
	testFixturesImplementation("org.springframework.boot:spring-boot-test")
	testFixturesApi("org.springframework.boot:spring-boot-testcontainers")
	testFixturesApi("org.testcontainers:testcontainers-postgresql")
	testFixturesImplementation("tools.jackson.dataformat:jackson-dataformat-yaml")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// The tests compile the repository's sample/ release and check it against its JSON Schema.
val sample = rootProject.file("../sample")
// The tests' Postgres runs the image of the db service in the repository's Compose file.
val compose = rootProject.file("../docker-compose.yml")

tasks.test {
	inputs.dir(sample).withPropertyName("sample").withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.file(compose).withPropertyName("compose").withPathSensitivity(PathSensitivity.RELATIVE)
	systemProperty("sample.dir", sample.path)
	systemProperty("compose.file", compose.path)
}

tasks.register<JavaExec>("releaseSchema") {
	description = "Writes the release format's JSON Schema, generated from its records, to sample/release.schema.json."
	classpath = sourceSets.test.get().runtimeClasspath
	mainClass = "art.liminis.release.ReleaseSchemaKt"
	args(sample.resolve("release.schema.json").path)
}
