import type { Metadata } from "next";
import { NextIntlClientProvider } from "next-intl";
import { getTranslations } from "next-intl/server";
import { Geist, Geist_Mono, Newsreader, Sofia_Sans_Extra_Condensed } from "next/font/google";
import { TooltipProvider } from "@/components/ui/tooltip";
import { pageLocale } from "@/i18n/page-locale";
import { preview, SITE_NAME, siteOrigin } from "@/lib/site";
import "../globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

// A book face for stories and descriptions.
const story = Newsreader({
  variable: "--font-newsreader",
  subsets: ["latin"],
  style: ["normal", "italic"],
});

// Condensed numerals for big years.
const display = Sofia_Sans_Extra_Condensed({
  variable: "--font-display-condensed",
  subsets: ["latin"],
  weight: ["800"],
});

export async function generateMetadata({ params }: LayoutProps<"/[locale]">): Promise<Metadata> {
  const locale = await pageLocale(params);
  const t = await getTranslations("site");
  const description = t("description");
  const { openGraph, twitter } = preview(SITE_NAME, description, locale);
  // The mark and the name, drawn once. Moments and people have their own (opengraph-image.ts); a file convention
  // here would be served at a broken address under [locale].
  const image = { url: "/opengraph-image.jpg", width: 1200, height: 630, alt: t("previewAlt") };
  return {
    metadataBase: await siteOrigin(),
    title: { default: SITE_NAME, template: `%s · ${SITE_NAME}` },
    description,
    openGraph: { ...openGraph, images: [image] },
    twitter: { ...twitter, images: [image] },
  };
}

export default async function RootLayout({ children, panel, params }: LayoutProps<"/[locale]">) {
  const locale = await pageLocale(params);
  return (
    <html
      lang={locale}
      className={`${geistSans.variable} ${geistMono.variable} ${story.variable} ${display.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        <NextIntlClientProvider>
          <TooltipProvider>
            {children}
            {panel}
          </TooltipProvider>
        </NextIntlClientProvider>
      </body>
    </html>
  );
}
