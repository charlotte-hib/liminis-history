import { defineRouting } from "next-intl/routing";

/**
 * English at the site's addresses as they always were, French under /fr. A first visit to an English address from a
 * browser that prefers French goes to its French page; choosing a language in the header sticks for the session.
 */
export const routing = defineRouting({
  locales: ["en", "fr"],
  defaultLocale: "en",
  localePrefix: "as-needed",
});
