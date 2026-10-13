import createClient from "openapi-fetch";
import type { paths } from "@/lib/api-schema";

/**
 * Browser-side fetches, for data that changes while the user interacts, typed from api/openapi.yaml.
 * Paths are relative: Next.js forwards these paths, and only these, to the backend (see `next.config.ts`).
 */
const api = createClient<paths>();

async function load<T>(request: Promise<{ data?: T; response: Response }>): Promise<T> {
  const { data, response } = await request;
  if (data === undefined) throw new Error(`GET ${response.url} failed: ${response.status}`);
  return data;
}

/** A few people alive in [year] in every region except [region]. */
export const fetchAliveElsewhere = (region: string, year: number, signal?: AbortSignal) =>
  load(api.GET("/api/years/{year}/people", { params: { path: { year }, query: { exclude: region } }, signal }));

/** A draw of the people alive in [year] of the moment [id]: 0 ranks them, later draws show others. */
export const fetchMomentPeople = (id: string, year: number, draw: number, signal?: AbortSignal) =>
  load(api.GET("/api/moments/{id}/people", { params: { path: { id }, query: { year, draw } }, signal }));

export const fetchEra = (id: string, signal?: AbortSignal) =>
  load(api.GET("/api/eras/{id}", { params: { path: { id } }, signal }));

export const fetchSearch = (query: string, signal?: AbortSignal) =>
  load(api.GET("/api/search", { params: { query: { q: query } }, signal }));
