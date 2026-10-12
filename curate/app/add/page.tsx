import { AddAnecdote } from "@/components/add-anecdote";

/** An anecdote the curator heard or read, added to its queue for review. */
export default function AddPage() {
  return (
    <main className="mx-auto flex w-full max-w-3xl flex-col gap-5 px-10 py-8">
      <h1 className="text-[22px] font-semibold">Add an anecdote</h1>
      <AddAnecdote />
    </main>
  );
}
