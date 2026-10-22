import { connection } from "next/server";
import createClient from "openapi-fetch";
import { getLocale } from "next-intl/server";
import { cache } from "react";
import type { paths } from "@/lib/api-schema";
import { BACKEND_URL } from "@/lib/backend-url";
import type { Language } from "@/lib/types";

/** The backend, typed from api/openapi.yaml: paths, parameters and responses. */
const api = createClient<paths>({ baseUrl: BACKEND_URL });

/** Server-side fetch. Returns null for a 404 so pages can call `notFound()`. */
async function load<T>(request: () => Promise<{ data?: T; response: Response }>): Promise<T | null> {
  // Data lives in the backend, so render at request time, not at build time.
  await connection();
  const { data, response } = await request();
  if (response.status === 404) return null;
  if (data === undefined) throw new Error(`GET ${response.url} failed: ${response.status}`);
  return data;
}

/** The page's language, which the records come back in where the release has them in it. */
const lang = async () => (await getLocale()) as Language;

export const getMoments = async () => (await load(async () => api.GET("/api/moments", { params: { query: { lang: await lang() } } }))) ?? [];
// Cached per request: a page and its metadata ask for the same moment or person.
export const getMoment = cache((id: string) =>
  load(async () => api.GET("/api/moments/{id}", { params: { path: { id }, query: { lang: await lang() } } })),
);
export const getStory = (id: string) =>
  load(async () => api.GET("/api/moments/{id}/story", { params: { path: { id }, query: { lang: await lang() } } }));
export const getPerson = cache((slug: string, year?: number) =>
  load(async () => api.GET("/api/people/{slug}", { params: { path: { slug }, query: { year: year || undefined, lang: await lang() } } })),
);
