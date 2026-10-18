/** Where the curator lands after signing out, or after a sign-in that failed. */
export default async function SignedOut({ searchParams }: PageProps<"/auth/signed-out">) {
  const failed = (await searchParams).failed !== undefined;
  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-3 px-10 py-8">
      <h1 className="page-title">{failed ? "The sign-in did not go through" : "Signed out"}</h1>
      <p className="text-muted">
        {failed ? "Is auth running, and do you still have access? Its log says what went wrong." : "Your session has ended here and at auth."}
      </p>
      {/* A plain link: the sign-in leaves curate for auth, which a client-side navigation cannot do. */}
      <a href="/auth/sign-in" className="button self-start">
        Sign in with your passkey
      </a>
    </main>
  );
}
