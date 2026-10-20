"use client";

import { ArrowLeft, ChevronRight } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { type ReactNode, createContext, useCallback, useContext, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { DetailPanel } from "@/components/detail-panel";
import { openingFullPageFromPanel, personSource, track } from "@/lib/analytics";

/** `href` already holds the language (`/fr/person/x`), so the links and router here are Next.js's own. */
type Step = { slug: string; name: string; href: string };
/** Carried from the panel to the full page, in that page's history entry. `origin` names the page the panel was opened over. */
type Trail = { origin: string | null; steps: Step[] };

const TrailContext = createContext<{ visit: (step: Step) => void; openFullPage: (href: string) => void }>({
  visit: () => {},
  openFullPage: () => {},
});

const TRAIL_KEY = "person-trail";

// Pages stay mounted under a panel, so the last one to register is the one the panel opens over.
let currentOrigin: string | null = null;

/** Names the current page as where a person's panel was opened from, for the trail on their full page. */
export function TrailOrigin({ label }: { label: string }) {
  useEffect(() => {
    currentOrigin = label;
  }, [label]);
  return null;
}

/**
 * A person's page opened over the current one, so it keeps its place (a story stays on its card).
 * Moving from person to person leaves a trail at the top, to step back along it. Closing goes back to
 * where the panel was opened, which takes the URL back too.
 */
export function PersonPanel({ kicker, children }: { kicker: string; children: ReactNode }) {
  const router = useRouter();
  const [open, setOpen] = useState(true);
  const [trail, setTrail] = useState<Step[]>([]);
  const [origin] = useState(() => currentOrigin);
  const t = useTranslations("common");

  // The first person is counted as opened from the page underneath (or the search box), the next ones from the panel.
  const opened = useRef(false);

  // Revisiting someone already on the trail cuts it back to them.
  const visit = useCallback((step: Step) => {
    track({ name: "person_panel_opened", source: opened.current ? "panel" : personSource() });
    opened.current = true;
    setTrail((current) => {
      const at = current.findIndex((other) => other.slug === step.slug);
      return at === -1 ? [...current, step] : current.slice(0, at + 1);
    });
  }, []);

  // The full page is a different render of the same URL, so it needs a real page load. Replacing the panel's
  // history entry keeps Back going to the page the panel was opened over, and the trail goes along.
  const openFullPage = useCallback(
    (href: string) => {
      try {
        sessionStorage.setItem(TRAIL_KEY, JSON.stringify({ origin, steps: trail } satisfies Trail));
      } catch {}
      openingFullPageFromPanel();
      window.location.replace(href);
    },
    [origin, trail],
  );

  return (
    <DetailPanel open={open} onOpenChange={setOpen} onOpenChangeComplete={(isOpen) => !isOpen && router.back()} kicker={kicker}>
      {trail.length > 1 && (
        <nav aria-label={t("yourPath")} className="-mt-1 mb-5 flex flex-wrap items-center gap-x-1 gap-y-1 text-sm">
          {trail.map((step, index) => (
            <span key={step.slug} className="inline-flex items-center gap-1">
              {index > 0 && <ChevronRight aria-hidden className="size-3.5 text-muted-foreground" />}
              {index === trail.length - 1 ? (
                <span aria-current="page" className="font-medium">{step.name}</span>
              ) : (
                <button type="button" onClick={() => router.replace(step.href)} className="text-muted-foreground underline-offset-4 hover:text-foreground hover:underline">
                  {step.name}
                </button>
              )}
            </span>
          ))}
        </nav>
      )}
      <TrailContext value={{ visit, openFullPage }}>{children}</TrailContext>
    </DetailPanel>
  );
}

/** Marks the person shown in the panel as a step on the trail, and starts them at the top of the panel. */
export function TrailStep(step: Step) {
  const { visit } = useContext(TrailContext);
  const marker = useRef<HTMLSpanElement>(null);
  const { slug, name, href } = step;
  useEffect(() => {
    visit({ slug, name, href });
    marker.current?.closest("[data-panel-scroll]")?.scrollTo({ top: 0 });
  }, [visit, slug, name, href]);
  return <span ref={marker} hidden />;
}

/** Opens the full page of the person in the panel, keeping the way back. */
export function FullPageButton({ href, className, children }: { href: string; className?: string; children: ReactNode }) {
  const { openFullPage } = useContext(TrailContext);
  return (
    <a
      href={href}
      className={className}
      onClick={(event) => {
        if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        openFullPage(href);
      }}
    >
      {children}
    </a>
  );
}

/** Moves the trail the panel left into this page's history entry, which a reload or Back keeps. */
function adoptTrail(onChange: () => void) {
  try {
    const stored = sessionStorage.getItem(TRAIL_KEY);
    if (stored) {
      sessionStorage.removeItem(TRAIL_KEY);
      // Keeping Next.js's own entries in the state makes this invisible to its router.
      window.history.replaceState({ ...window.history.state, [TRAIL_KEY]: JSON.parse(stored) }, "");
      onChange();
    }
  } catch {}
  return () => {};
}

const trailInHistory = (): Trail | null => window.history.state?.[TRAIL_KEY] ?? null;

/**
 * On a person's full page opened from the panel: where it was opened from and the people on the way. The trail
 * moves into the page's history entry, so it survives a reload and Back, but not a shared link.
 */
export function FullPageTrail({ slug }: { slug: string }) {
  const stored = useSyncExternalStore(adoptTrail, trailInHistory, () => null);
  const trail = stored?.steps.at(-1)?.slug === slug ? stored : null;
  const t = useTranslations("common");

  if (!trail || (!trail.origin && trail.steps.length < 2)) return null;
  const earlier = trail.steps.slice(0, -1);
  const current = trail.steps.at(-1)!;

  return (
    <nav aria-label={t("yourPath")} className="flex flex-wrap items-center gap-x-1 gap-y-1 text-sm">
      {trail.origin && (
        <button
          type="button"
          onClick={() => window.history.back()}
          className="inline-flex items-center gap-1 text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
        >
          <ArrowLeft aria-hidden className="size-3.5" />
          {trail.origin}
        </button>
      )}
      {[...earlier, current].map((step, index) => (
        <span key={step.slug} className="inline-flex items-center gap-1">
          {(index > 0 || trail.origin) && <ChevronRight aria-hidden className="size-3.5 text-muted-foreground" />}
          {step === current ? (
            <span aria-current="page" className="font-medium">{step.name}</span>
          ) : (
            <Link href={step.href} className="text-muted-foreground underline-offset-4 hover:text-foreground hover:underline">
              {step.name}
            </Link>
          )}
        </span>
      ))}
    </nav>
  );
}
