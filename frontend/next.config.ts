import type { NextConfig } from "next";
import { BACKEND_URL } from "./lib/backend-url";

/** The backend paths the browser calls (`lib/api-browser.ts` and `lib/analytics.ts`). */
const BROWSER_API = ["/api/search", "/api/eras/:id", "/api/moments/:id/people", "/api/years/:year/people", "/api/events"];

const nextConfig: NextConfig = {
  // A self-contained server (.next/standalone/server.js) for the Docker image.
  output: "standalone",
  // The browser only talks to this site: the few API paths it calls are forwarded to the Spring Boot backend,
  // so the app works behind any single public address (a tunnel, a reverse proxy) without CORS. Only those:
  // the rest of the API is fetched by this server while rendering, so it is not a public download of the data.
  async rewrites() {
    return BROWSER_API.map((path) => ({ source: path, destination: `${BACKEND_URL}${path}` }));
  },
};

export default nextConfig;
