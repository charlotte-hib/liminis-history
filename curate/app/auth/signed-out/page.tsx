/** Where the curator lands after signing out, or after a sign-in that failed. */
export default async function SignedOut({ searchParams }: PageProps<"/auth/signed-out">) {
  const failed = (await searchParams).failed !== undefined;
  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-3 px-10 py-8">
      <h1 className="text-[22px] font-semibold">{failed ? "The sign-in did not go through" : "Signed out"}</h1>
      <p className="text-muted">
        {failed ? "Is auth running? Its log says what went wrong." : "Your session has ended here and at auth."}
      </p>
      {/* A plain link: the sign-in leaves curate for auth, which a client-side navigation cannot do. */}
      <a href="/auth/sign-in" className="self-start rounded-md border border-line bg-surface px-3 py-1.5 hover:border-muted">
        Sign in with your passkey
      </a>
    </main>
  );
}
