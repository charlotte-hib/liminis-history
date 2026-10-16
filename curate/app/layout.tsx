import type { Metadata } from "next";
import { Geist, Geist_Mono, Newsreader } from "next/font/google";
import Link from "next/link";
import { Nav } from "@/components/nav";
import "./globals.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });
// Text visitors will read is set in the site's story face.
const newsreader = Newsreader({ variable: "--font-newsreader", subsets: ["latin"], style: ["normal", "italic"] });

export const metadata: Metadata = {
  title: "Liminis reading room",
  description: "The curator's review of claims, on this computer only.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${geistSans.variable} ${geistMono.variable} ${newsreader.variable} h-full antialiased`}>
      <body className="flex min-h-full flex-col font-sans text-sm">
        <header className="flex flex-wrap items-center gap-x-7 gap-y-2 border-b border-line px-10 py-[18px]">
          <Link href="/" className="font-serif text-[22px] tracking-[0.01em]">
            Liminis <span className="text-quiet italic">reading room</span>
          </Link>
          <Nav />
          <form method="post" action="/auth/sign-out" className="ml-auto">
            <button type="submit" className="text-muted hover:text-ink">
              Sign out
            </button>
          </form>
        </header>
        {children}
      </body>
    </html>
  );
}
