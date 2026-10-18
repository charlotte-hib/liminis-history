"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const LINKS = [
  { href: "/", name: "Inbox", also: "/review" },
  { href: "/moments", name: "Moments" },
  { href: "/add", name: "Add an anecdote" },
  { href: "/publish", name: "Publish" },
];

/** The admin's alone. */
const TEAM = { href: "/team", name: "Team" };

/** The main sections, the one open underlined in lamp gold. */
export function Nav({ admin }: { admin: boolean }) {
  const path = usePathname();
  const open = ({ href, also }: { href: string; also?: string }) =>
    href === "/" ? path === "/" || (also !== undefined && path.startsWith(also)) : path.startsWith(href);

  return (
    <nav aria-label="Main" className="flex flex-wrap gap-x-[22px] gap-y-1">
      {(admin ? [...LINKS, TEAM] : LINKS).map((link: { href: string; name: string; also?: string }) => (
        <Link
          key={link.href}
          href={link.href}
          aria-current={open(link) ? "page" : undefined}
          className="border-b-2 border-transparent pb-0.5 text-muted hover:text-ink aria-[current]:border-lamp aria-[current]:text-ink"
        >
          {link.name}
        </Link>
      ))}
    </nav>
  );
}
