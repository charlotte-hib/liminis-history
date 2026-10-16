"use client";

import { ArrowLeft, ArrowRight, Play } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import Link from "next/link";
import { useState } from "react";
import { ArtworkCredit, ArtworkImage } from "@/components/artwork-image";
import { PersonAvatar } from "@/components/person-avatar";
import { buttonVariants } from "@/components/ui/button";
import type { MomentSummary } from "@/lib/types";
import { cn } from "@/lib/utils";
import { ageText, formatYear } from "@/lib/years";

type Props = { stories: MomentSummary[]; initialIndex: number };

/** The first screen: one story's painting, its hook and its cast, with a way to play it or step through others. */
export function FeaturedStory({ stories, initialIndex }: Props) {
  const [index, setIndex] = useState(initialIndex);
  const story = stories[index];
  const step = (by: number) => setIndex((current) => (current + by + stories.length) % stories.length);

  return (
    <section aria-labelledby="featured-title" className="dark relative flex min-h-[min(88vh,52rem)] items-end overflow-hidden">
      <AnimatePresence initial={false}>
        <motion.div
          key={story.id}
          className="absolute inset-0"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.8 }}
        >
          {story.art && <ArtworkImage art={story.art} priority />}
        </motion.div>
      </AnimatePresence>
      {/* The gallery's dark wash in both themes, never a white veil; on paper a little lighter, to keep the painting vivid. */}
      <div className="absolute inset-0 bg-gradient-to-t from-background via-background/70 to-background/30 light:from-background/70 light:via-background/40 light:to-background/10" />

      <div className="relative mx-auto w-full max-w-6xl px-4 pt-32 pb-12">
        <p className="text-xs font-medium tracking-widest text-lamp uppercase">Featured story</p>
        <h1 id="featured-title" className="mt-2 font-story text-6xl leading-none font-medium sm:text-8xl">
          {story.place}, <span className="text-muted-foreground italic">{story.period}</span>
        </h1>
        <p className="mt-4 max-w-xl font-story text-xl text-foreground/90 sm:text-2xl">{story.hook}</p>

        <ul className="mt-6 flex flex-wrap gap-4">
          {story.cast.map((person) => (
            <li key={person.slug}>
              <Link
                href={`/person/${person.slug}?year=${story.focusYear}`}
                className="group -m-1.5 flex items-center gap-2 rounded-full p-1.5 pr-3 text-sm hover:bg-background/50"
              >
                <PersonAvatar person={person} className="size-9" />
                <span>
                  <span className="group-hover:underline">{person.name}</span>
                  <span className="block text-xs text-muted-foreground tabular-nums">
                    {ageText(person, story.focusYear)} in {formatYear(story.focusYear)}
                  </span>
                </span>
              </Link>
            </li>
          ))}
        </ul>

        <div className="mt-8 flex flex-wrap items-center gap-3">
          <Link href={`/moment/${story.id}/story`} className={cn(buttonVariants({ size: "lg" }), "rounded-full px-5")}>
            <Play /> Play the story
          </Link>
          <Link href={`/moment/${story.id}`} className={cn(buttonVariants({ variant: "outline", size: "lg" }), "rounded-full px-5")}>
            Explore the moment
          </Link>
          <div className="ml-auto flex items-center gap-2">
            <span className="mr-1 font-mono text-xs text-muted-foreground">
              {index + 1} / {stories.length}
            </span>
            <button type="button" onClick={() => step(-1)} aria-label="Previous story" className={cn(buttonVariants({ variant: "outline", size: "icon" }), "rounded-full")}>
              <ArrowLeft />
            </button>
            <button type="button" onClick={() => step(1)} aria-label="Next story" className={cn(buttonVariants({ variant: "outline", size: "icon" }), "rounded-full")}>
              <ArrowRight />
            </button>
          </div>
        </div>
        {story.art && <ArtworkCredit art={story.art} className="mt-6 block" />}
      </div>
    </section>
  );
}
