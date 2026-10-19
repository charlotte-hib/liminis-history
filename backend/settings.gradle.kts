rootProject.name = "backend"

// core: the model every app shares (entities, repositories, helpers, loading a release, Wikipedia enrichment).
// site: the public API, the site's image.
// workbench: the curators' tools, on the curator's computer, or in an image of its own for the review on the server.
// auth: the curators' sign-in to the workbench, likewise.
include("core", "site", "workbench", "auth")

dependencyResolutionManagement {
	repositories {
		mavenCentral()
	}
}
