import { LastDecisionProvider } from "@/components/last-decision";

/** The list and each person's page share the last decision, so the next person's page can undo it. */
export default function PeopleLayout({ children }: LayoutProps<"/people">) {
  return <LastDecisionProvider>{children}</LastDecisionProvider>;
}
