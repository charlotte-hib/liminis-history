"use client";

import Link, { useLinkStatus } from "next/link";
import { usePathname } from "next/navigation";

const LINKS = [
  { href: "/", name: "Inbox", also: "/review" },
  { href: "/moments", name: "Moments" },
  { href: "/add", name: "Add an anecdote" },
  { href: "/publish", name: "Publish" },
];

/** The admin's alone. */
const TEAM = { href: "/team", name: "Team" };

/** A dot that pulses beside the section pressed while its page loads: some wait on the workbench or on auth. */
function Loading() {
  const { pending } = useLinkStatus();
  return pending && <span aria-hidden className="absolute top-1/2 -right-3 size-1.5 -translate-y-1/2 animate-pulse rounded-full bg-lamp" />;
}

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
          className="relative border-b-2 border-transparent pb-0.5 text-muted hover:text-ink aria-[current]:border-lamp aria-[current]:text-ink"
        >
          {link.name}
          <Loading />
        </Link>
      ))}
    </nav>
  );
}
