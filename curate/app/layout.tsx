import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import Link from "next/link";
import "./globals.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

export const metadata: Metadata = {
  title: "curate",
  description: "The curator's review of claims, on this computer only.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}>
      <body className="flex min-h-full flex-col font-sans text-sm">
        <header className="flex h-13 items-center gap-7 border-b border-line bg-bar px-6">
          <Link href="/" className="font-mono text-[15px] font-medium">
            curate
          </Link>
          <nav aria-label="Main" className="flex gap-5 text-muted">
            <Link href="/" className="hover:text-ink">
              Inbox
            </Link>
            <Link href="/moments" className="hover:text-ink">
              Moments
            </Link>
            <Link href="/add" className="hover:text-ink">
              Add a claim
            </Link>
            <Link href="/publish" className="hover:text-ink">
              Publish
            </Link>
          </nav>
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
