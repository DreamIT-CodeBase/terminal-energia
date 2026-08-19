import type { NextConfig } from "next";

// The app is no longer a static export: Microsoft sign-in, the session cookie
// and the system API key all need a server, so it runs as a Next.js server.
const nextConfig: NextConfig = {
  output: 'standalone',
};

export default nextConfig;
