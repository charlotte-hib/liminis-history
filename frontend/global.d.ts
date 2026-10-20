import type { routing } from "@/i18n/routing";
import type messages from "@/messages/en.json";

// Typed message keys and locales for next-intl's hooks and functions.
declare module "next-intl" {
  interface AppConfig {
    Locale: (typeof routing.locales)[number];
    Messages: typeof messages;
  }
}
