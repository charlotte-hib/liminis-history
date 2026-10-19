// auth: the curators' sign-in, on the curator's computer or in an image for the server (backend/Dockerfile, target
// auth). An OAuth 2.1 and OpenID Connect authorization server on localhost:9000: accounts and passkeys, JWTs for the
// workbench API, a client for curate and one for wb, and the accounts API curate's Team page calls.
plugins {
	kotlin("jvm")
	kotlin("plugin.spring")
	id("org.springframework.boot")
	id("io.spring.dependency-management")
	id("org.openapi.generator")
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
	implementation("org.springframework.boot:spring-boot-starter-validation")
	// Its pages (sign in, the one-time link, passkeys), in curate's look.
	implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
	// Clients, authorizations, passkeys, one-time tokens and signing keys in schema auth of the workbench's Postgres.
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")
	// A request limit per visitor, its buckets forgotten once idle (RateLimit).
	implementation("com.bucket4j:bucket4j_jdk17-core:8.21.0")
	implementation("com.github.ben-manes.caffeine:caffeine")
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

// The accounts API's contract, api/auth.yaml at the repository root, generates its controller interface and models
// (art.liminis.auth.api), as for the workbench's.
val apiSpec = rootProject.layout.projectDirectory.file("../api/auth.yaml")
val generatedApi = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
	generatorName = "kotlin-spring"
	inputSpec = apiSpec
	outputDir = generatedApi
	apiPackage = "art.liminis.auth.api"
	modelPackage = "art.liminis.auth.api.model"
	modelNameSuffix = "Dto"
	globalProperties = mapOf("apis" to "", "models" to "")
	generateApiDocumentation = false
	generateModelDocumentation = false
	generateApiTests = false
	generateModelTests = false
	configOptions = mapOf(
		"useSpringBoot4" to "true",
		"interfaceOnly" to "true",
		"skipDefaultInterface" to "true",
		"useTags" to "true",
		"requestMappingMode" to "api_interface",
		"useResponseEntity" to "false",
		"useBeanValidation" to "true",
		"useSpringBuiltInValidation" to "true",
		"documentationProvider" to "none",
		"annotationLibrary" to "none",
		"exceptionHandler" to "false",
		"gradleBuildFile" to "false",
	)
}

kotlin.sourceSets.main {
	kotlin.srcDir(generatedApi.map { it.dir("src/main/kotlin") })
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
	dependsOn(tasks.openApiGenerate)
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
