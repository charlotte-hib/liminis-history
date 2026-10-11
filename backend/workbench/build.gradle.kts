// The workbench: the curator's tools, on the Mac only, never in an image. A server on 127.0.0.1:8090 that the wb
// command line and the review app call: imports, the dataset, claims to review, story drafts.
plugins {
	kotlin("jvm")
	kotlin("plugin.spring")
	// Claims, sources and decisions are JPA entities (curation/CurationEntities.kt), as the site's model is in core.
	kotlin("plugin.allopen")
	kotlin("plugin.jpa")
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
	implementation(project(":core"))
	implementation("org.springframework.boot:spring-boot-starter-jackson")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	// Every request needs a bearer token: the workbench's own until the authorization server (auth) issues JWTs.
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("com.anthropic:anthropic-java:2.68.0")
	implementation("org.springframework.boot:spring-boot-starter-restclient")
	// One shared pace for every call to Wikimedia: rate limiter, bulkhead and retry, configured in application.yaml.
	implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
	// The workbench's own schemas, raw, dataset and curation, migrated apart from the release's (WorkbenchSchemas).
	implementation("org.flywaydb:flyway-core")
	// Which country a place lies in today: Natural Earth's borders in GeoJSON, read and searched with JTS.
	implementation("org.locationtech.jts:jts-core:1.20.0")
	implementation("org.locationtech.jts.io:jts-io-common:1.20.0")
	// Postgres on ./gradlew :workbench:bootRun: starts the db service of compose.workbench.yaml and connects to it.
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation(testFixtures(project(":core")))
	// A fake Wikimedia on a real port, answering with responses recorded from the real one.
	testImplementation("org.wiremock.integrations:wiremock-spring-boot:4.4.3")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.Embeddable")
}

// The workbench's API contract, api/workbench.yaml at the repository root, generates the controller interfaces and the
// models (dev.wholivedwhen.workbench.api), as for the site's.
val apiSpec = rootProject.layout.projectDirectory.file("../api/workbench.yaml")
val generatedApi = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
	generatorName = "kotlin-spring"
	inputSpec = apiSpec
	outputDir = generatedApi
	apiPackage = "dev.wholivedwhen.workbench.api"
	modelPackage = "dev.wholivedwhen.workbench.api.model"
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

tasks.withType<Test> {
	useJUnitPlatform()
}

// The release the workbench loads into its database: the repository's sample/.
val sample = rootProject.file("../sample")
// The tests' Postgres runs the image of the db service in the repository's Compose file (core's PostgresTestConfiguration).
val compose = rootProject.file("../docker-compose.yml")

tasks.test {
	inputs.dir(sample).withPropertyName("sample").withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.file(compose).withPropertyName("compose").withPathSensitivity(PathSensitivity.RELATIVE)
	systemProperty("app.release.dir", sample.path)
	systemProperty("compose.file", compose.path)
	// Not the curator's own token file, which a running workbench may be using.
	systemProperty("app.workbench.token-file", temporaryDir.resolve("workbench-token").path)
}

// Run from backend/, so story drafts land in backend/drafts as before. Its Postgres is compose.workbench.yaml, at the
// repository's root, not the site's backend/compose.yaml.
tasks.bootRun {
	workingDir = rootProject.projectDir
	systemProperty("app.release.dir", sample.path)
	systemProperty("spring.docker.compose.file", rootProject.file("../compose.workbench.yaml").path)
}
