// auth: the curator's sign-in, on the curator's computer only, never in an image. An OAuth 2.1 and OpenID Connect
// authorization server on localhost:9000: passkeys, JWTs for the workbench API, a client for curate and one for wb.
plugins {
	kotlin("jvm")
	kotlin("plugin.spring")
	id("org.springframework.boot")
	id("io.spring.dependency-management")
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

dependencies {
	// Spring Authorization Server, part of Spring Security since 7.0.
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-authorization-server")
	// Passkeys (WebAuthn), through webauthn4j.
	implementation("org.springframework.security:spring-security-webauthn")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	// Clients, authorizations, passkeys, one-time tokens and signing keys in schema auth of the workbench's Postgres.
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	// Postgres on ./gradlew :auth:bootRun: the db service of compose.workbench.yaml, shared with the workbench.
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation(testFixtures(project(":core")))
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

// The tests' Postgres runs the image of the db service in the repository's Compose file (core's PostgresTestConfiguration).
val compose = rootProject.file("../docker-compose.yml")

tasks.test {
	inputs.file(compose).withPropertyName("compose").withPathSensitivity(PathSensitivity.RELATIVE)
	systemProperty("compose.file", compose.path)
	// Not the curator's own secrets, which a running auth may be using.
	systemProperty("app.auth.secrets-dir", temporaryDir.resolve("secrets").path)
}

tasks.bootRun {
	systemProperty("spring.docker.compose.file", rootProject.file("../compose.workbench.yaml").path)
}
