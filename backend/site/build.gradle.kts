// The public API: the Spring Boot app in the production image.
plugins {
	kotlin("jvm")
	kotlin("plugin.spring")
	kotlin("kapt")
	id("org.springframework.boot")
	id("io.spring.dependency-management")
	id("org.openapi.generator")
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

val mapstructVersion = "1.6.3"

dependencies {
	implementation(project(":core"))
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.mapstruct:mapstruct:$mapstructVersion")
	kapt("org.mapstruct:mapstruct-processor:$mapstructVersion")
	// Swagger UI on ./gradlew bootRun only: developmentOnly stays out of the jar and the image.
	developmentOnly("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")
	// Postgres on ./gradlew bootRun: starts the db service of backend/compose.yaml and connects to it.
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation(testFixtures(project(":core")))
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

// The API contract, api/openapi.yaml at the repository root, generates the controller interfaces and the response
// models (art.liminis.api); the controllers implement the interfaces. Nothing else is generated.
val apiSpec = rootProject.layout.projectDirectory.file("../api/openapi.yaml")
val generatedApi = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
	generatorName = "kotlin-spring"
	inputSpec = apiSpec
	outputDir = generatedApi
	apiPackage = "art.liminis.api"
	modelPackage = "art.liminis.api.model"
	modelNameSuffix = "Dto"
	// Only apis and models: no supporting files (application, build file, README, exception handler).
	globalProperties = mapOf("apis" to "", "models" to "")
	generateApiDocumentation = false
	generateModelDocumentation = false
	generateApiTests = false
	generateModelTests = false
	// POST /api/events stays hand-written: it reads the raw body to cap its size and to accept sendBeacon's text/plain.
	openapiNormalizer = mapOf("FILTER" to "tag:moments|people|eras|regions|search")
	configOptions = mapOf(
		"useSpringBoot4" to "true",
		"interfaceOnly" to "true",
		"skipDefaultInterface" to "true",
		"useTags" to "true",
		"requestMappingMode" to "api_interface",
		"useResponseEntity" to "false",
		// The spec's constraints (minimum, pattern, maxLength) become Bean Validation annotations on the interfaces,
		// which Spring MVC's built-in method validation enforces: a request outside them gets a 400.
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

kapt {
	arguments {
		arg("mapstruct.defaultComponentModel", "spring")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// The release the tests and bootRun load: the repository's sample/. Compose mounts it into the container.
val sample = rootProject.file("../sample")
// The tests' Postgres runs the image of the db service in the repository's Compose file (core's PostgresTestConfiguration).
val compose = rootProject.file("../docker-compose.yml")

tasks.test {
	inputs.dir(sample).withPropertyName("sample").withPathSensitivity(PathSensitivity.RELATIVE)
	inputs.file(compose).withPropertyName("compose").withPathSensitivity(PathSensitivity.RELATIVE)
	systemProperty("app.release.dir", sample.path)
	systemProperty("compose.file", compose.path)
}

// Run from backend/, where Spring Boot's Docker Compose support finds compose.yaml.
tasks.bootRun {
	workingDir = rootProject.projectDir
	systemProperty("app.release.dir", sample.path)
	// Swagger UI at http://localhost:8080/swagger-ui.html shows the spec itself, served from api/, rather than the one
	// springdoc builds from the code.
	systemProperty("spring.web.resources.static-locations", "classpath:/static/,file:${apiSpec.asFile.parent}/")
	systemProperty("springdoc.swagger-ui.url", "/openapi.yaml")
}
