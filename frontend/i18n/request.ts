import { hasLocale } from "next-intl";
import { getRequestConfig } from "next-intl/server";
import { routing } from "@/i18n/routing";
import en from "@/messages/en.json";
import fr from "@/messages/fr.json";

// Fails the build when the French messages miss a key the English ones have.
const MESSAGES: Record<(typeof routing.locales)[number], typeof en> = { en, fr };

export default getRequestConfig(async ({ requestLocale }) => {
  const requested = await requestLocale;
  const locale = hasLocale(routing.locales, requested) ? requested : routing.defaultLocale;
  return { locale, messages: MESSAGES[locale] };
});
