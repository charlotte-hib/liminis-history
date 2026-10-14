rootProject.name = "backend"

// core: the model every app shares (entities, repositories, helpers, loading a release, Wikipedia enrichment).
// site: the public API, and the only project in the production image.
// workbench: the curator's tools, on the Mac only, never in an image.
// auth: the curator's sign-in to the workbench, on the Mac only, never in an image.
include("core", "site", "workbench", "auth")

dependencyResolutionManagement {
	repositories {
		mavenCentral()
	}
}
