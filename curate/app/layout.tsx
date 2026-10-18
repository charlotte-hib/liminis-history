import type { Metadata } from "next";
import { cookies } from "next/headers";
import { Geist, Geist_Mono, Newsreader } from "next/font/google";
import Link from "next/link";
import { Nav } from "@/components/nav";
import { account, readSession, SESSION_COOKIE } from "@/lib/auth";
import "./globals.css";

const geistSans = Geist({ variable: "--font-geist-sans", subsets: ["latin"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });
// Text visitors will read is set in the site's story face.
const newsreader = Newsreader({ variable: "--font-newsreader", subsets: ["latin"], style: ["normal", "italic"] });

export const metadata: Metadata = {
  title: "Liminis reading room",
  description: "The curators' review of claims, on this computer only.",
};

export default async function RootLayout({ children }: LayoutProps<"/">) {
  const session = await readSession((await cookies()).get(SESSION_COOKIE)?.value);
  const signedIn = session && account(session);

  return (
    <html lang="en" className={`${geistSans.variable} ${geistMono.variable} ${newsreader.variable} h-full antialiased`}>
      <body className="flex min-h-full flex-col font-sans text-sm">
        <header className="flex flex-wrap items-center gap-x-7 gap-y-2 border-b border-line px-10 py-[18px]">
          <Link href="/" className="font-serif text-[22px] tracking-[0.01em]">
            Liminis <span className="text-quiet italic">reading room</span>
          </Link>
          <Nav admin={signedIn?.admin ?? false} />
          <form method="post" action="/auth/sign-out" className="ml-auto flex items-center gap-4">
            {signedIn?.name && <span className="text-quiet">Signed in as {signedIn.name}</span>}
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
