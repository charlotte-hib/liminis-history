// Plugin versions for every project. Each project applies the plugins it needs.
plugins {
	kotlin("jvm") version "2.4.20" apply false
	kotlin("plugin.allopen") version "2.4.20" apply false
	kotlin("plugin.spring") version "2.4.20" apply false
	kotlin("plugin.jpa") version "2.4.20" apply false
	kotlin("kapt") version "2.4.20" apply false
	id("org.springframework.boot") version "4.1.1" apply false
	id("io.spring.dependency-management") version "1.1.7" apply false
	id("org.openapi.generator") version "7.26.0" apply false
}

subprojects {
	group = "art.liminis"
	version = "0.0.1-SNAPSHOT"
	// Past the versions Spring Boot 4.1.1 manages, for known vulnerabilities fixed since: Tomcat 11.0.24 has critical
	// ones, Jackson 3.1.5 high ones. Remove each once Spring Boot manages that version or a newer one.
	extra["tomcat.version"] = "11.0.27"
	extra["jackson-bom.version"] = "3.1.7"
}
