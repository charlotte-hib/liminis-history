import { createNavigation } from "next-intl/navigation";
import { routing } from "@/i18n/routing";

/** Next.js's links and router, keeping the visitor's language: `/person/x` leads to `/fr/person/x` on the French site. */
export const { Link, getPathname, redirect, usePathname, useRouter } = createNavigation(routing);
