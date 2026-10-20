import type { NextConfig } from "next";
import createNextIntlPlugin from "next-intl/plugin";
import { BACKEND_URL } from "./lib/backend-url";

/** The backend paths the browser calls (`lib/api-browser.ts` and `lib/analytics.ts`). */
const BROWSER_API = ["/api/search", "/api/eras/:id", "/api/moments/:id/people", "/api/years/:year/people", "/api/events"];

const nextConfig: NextConfig = {
  // A self-contained server (.next/standalone/server.js) for the Docker image.
  output: "standalone",
  // proxy.ts drops Next-Url from a person's page to another person's, which no longer matches the `_rsc` hash the
  // router sent, and this check would redirect it. The check keeps a cache that ignores `Vary` from serving one
  // navigation's response to another; none sits in front of the site (Caddy caches nothing, and these responses are
  // `private, no-store`). Turn it back on before putting a caching CDN in front.
  experimental: { validateRSCRequestHeaders: false },
  // The browser only talks to this site: the few API paths it calls are forwarded to the Spring Boot backend,
  // so the app works behind any single public address (a tunnel, a reverse proxy) without CORS. Only those:
  // the rest of the API is fetched by this server while rendering, so it is not a public download of the data.
  async rewrites() {
    return {
      // English pages live under app/[locale] as /en, at addresses without it. proxy.ts sends them there, but
      // prefetches skip it (see its matcher), so they are sent there here too.
      beforeFiles: [
        { source: "/", destination: "/en" },
        { source: "/:path((?!en(?:/|$)|fr(?:/|$)|api/|_next/)[^.]+)", destination: "/en/:path" },
      ],
      afterFiles: BROWSER_API.map((path) => ({ source: path, destination: `${BACKEND_URL}${path}` })),
    };
  },
};

export default createNextIntlPlugin("./i18n/request.ts")(nextConfig);
