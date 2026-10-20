"use client";

import { Drawer } from "@base-ui/react/drawer";
import { X } from "lucide-react";
import { useTranslations } from "next-intl";
import type { ReactNode } from "react";
import { buttonVariants } from "@/components/ui/button";
import { useMediaQuery } from "@/lib/use-media-query";
import { cn } from "@/lib/utils";

type Props = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Called once the panel has finished opening or closing. */
  onOpenChangeComplete?: (open: boolean) => void;
  /** What the panel is about, e.g. "Everyday life". */
  kicker: string;
  children: ReactNode;
};

const EASE = "ease-[cubic-bezier(0.32,0.72,0,1)]";

/**
 * More about someone or something without leaving the page: a bottom sheet on phones, a side panel on
 * wider screens. Swipe it away, tap outside or press Escape to close it.
 */
export function DetailPanel({ open, onOpenChange, onOpenChangeComplete, kicker, children }: Props) {
  const wide = useMediaQuery("(min-width: 40rem)");
  const t = useTranslations("common");

  return (
    <Drawer.Root open={open} onOpenChange={onOpenChange} onOpenChangeComplete={onOpenChangeComplete} swipeDirection={wide ? "right" : "down"}>
      <Drawer.Portal>
        <Drawer.Backdrop
          className={cn(
            "fixed inset-0 z-[60] min-h-dvh bg-black opacity-[calc(0.6*(1-var(--drawer-swipe-progress)))] transition-opacity duration-[450ms]",
            "data-starting-style:opacity-0 data-ending-style:opacity-0 data-swiping:duration-0",
            EASE,
          )}
        />
        <Drawer.Viewport className="fixed inset-0 z-[60] flex items-end sm:items-stretch sm:justify-end">
          <Drawer.Popup
            className={cn(
              "flex max-h-[85dvh] w-full flex-col overflow-hidden rounded-t-2xl border-t bg-background text-foreground shadow-2xl outline-none",
              "sm:h-full sm:max-h-none sm:w-[28rem] sm:rounded-none sm:border-t-0 sm:border-l",
              "[transform:translate(var(--drawer-swipe-movement-x),var(--drawer-swipe-movement-y))] transition-transform duration-[450ms]",
              "data-starting-style:translate-y-full data-ending-style:translate-y-full",
              "sm:data-starting-style:translate-x-full sm:data-starting-style:translate-y-0 sm:data-ending-style:translate-x-full sm:data-ending-style:translate-y-0",
              "data-ending-style:duration-[calc(var(--drawer-swipe-strength)*400ms)] data-swiping:select-none",
              EASE,
            )}
          >
            <div aria-hidden className="mx-auto mt-3 h-1 w-10 shrink-0 rounded-full bg-muted-foreground/40 sm:hidden" />
            <header className="flex shrink-0 items-center justify-between gap-4 px-5 pt-3 sm:pt-5">
              <Drawer.Title className="text-xs font-medium tracking-[0.12em] text-lamp uppercase">{kicker}</Drawer.Title>
              <Drawer.Close aria-label={t("close")} className={cn(buttonVariants({ variant: "ghost", size: "icon" }), "rounded-full")}>
                <X />
              </Drawer.Close>
            </header>
            <Drawer.Content data-panel-scroll className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 pt-2 pb-[calc(2rem+env(safe-area-inset-bottom))]">
              {children}
            </Drawer.Content>
          </Drawer.Popup>
        </Drawer.Viewport>
      </Drawer.Portal>
    </Drawer.Root>
  );
}
