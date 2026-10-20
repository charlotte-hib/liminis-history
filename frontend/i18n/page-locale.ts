import { hasLocale, type Locale } from "next-intl";
import { setRequestLocale } from "next-intl/server";
import { notFound } from "next/navigation";
import { routing } from "@/i18n/routing";

/**
 * The language of a layout or page under app/[locale], from its address, made the language of everything it renders.
 * Without it, next-intl falls back to English where proxy.ts did not run: a prefetch renders French pages in English.
 * Every layout and page calls it first.
 */
export async function pageLocale(params: Promise<{ locale: string }>): Promise<Locale> {
  const { locale } = await params;
  if (!hasLocale(routing.locales, locale)) notFound();
  setRequestLocale(locale);
  return locale;
}
