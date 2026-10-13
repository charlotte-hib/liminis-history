import type { Metadata } from "next";
import { Geist, Geist_Mono, Newsreader, Sofia_Sans_Extra_Condensed } from "next/font/google";
import { TooltipProvider } from "@/components/ui/tooltip";
import { preview, SITE_DESCRIPTION, SITE_NAME, siteOrigin } from "@/lib/site";
import "./globals.css";

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

export async function generateMetadata(): Promise<Metadata> {
  return {
    metadataBase: await siteOrigin(),
    title: { default: SITE_NAME, template: `%s · ${SITE_NAME}` },
    description: SITE_DESCRIPTION,
    ...preview(SITE_NAME, SITE_DESCRIPTION),
  };
}

export default function RootLayout({ children, panel }: LayoutProps<"/">) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} ${story.variable} ${display.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        <TooltipProvider>
          {children}
          {panel}
        </TooltipProvider>
      </body>
    </html>
  );
}
