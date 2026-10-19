import type { NextConfig } from "next";

// curate runs on the curator's computer (npm run dev or start, on 127.0.0.1:3100, opened as http://localhost:3100), or
// on the server from its image (curate/Dockerfile, compose.review.yaml).
const nextConfig: NextConfig = {
  // A self-contained server (.next/standalone/server.js) for the image. npm run start still works, with a warning.
  output: "standalone",
};

export default nextConfig;
