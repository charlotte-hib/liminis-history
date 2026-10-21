import { headers } from "next/headers";

/** The name visitors see: in the tab, and on link previews. */
export const SITE_NAME = "Liminis";

/**
 * The address this request came in on, for the absolute URLs link previews need. The reverse proxy passes the
 * visitor's host and protocol on, so the site needs no setting of its own address and works on any of them.
 */
export async function siteOrigin() {
  const request = await headers();
  const host = request.get("x-forwarded-host") ?? request.get("host") ?? "localhost:3000";
  const protocol = request.get("x-forwarded-proto") ?? "http";
  return new URL(`${protocol}://${host}`);
}

const OG_LOCALES: Record<string, string> = { en: "en_GB", fr: "fr_FR" };

/** What a link preview shows as its title and text. A page's own `openGraph` replaces its layout's, so each sets all. */
export const preview = (title: string, description: string, locale: string) => ({
  openGraph: { title, description, siteName: SITE_NAME, type: "website" as const, locale: OG_LOCALES[locale] },
  twitter: { card: "summary_large_image" as const, title, description },
});
