import "server-only";
import createClient from "openapi-fetch";
import type { components, paths } from "@/lib/auth-schema";
import { AUTH_INTERNAL_URL } from "@/lib/auth";
import { signIn } from "@/lib/workbench";

export type Account = components["schemas"]["Account"];
export type Invitation = components["schemas"]["Invitation"];
export type Team = components["schemas"]["Team"];

/** auth's accounts API, typed from api/auth.yaml: the team, for the admin's token only. */
export const accounts = createClient<paths>({ baseUrl: AUTH_INTERNAL_URL.origin, cache: "no-store" });
accounts.use(signIn);
