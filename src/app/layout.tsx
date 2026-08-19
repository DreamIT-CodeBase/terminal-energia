import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Fuel Price Labelling",
  description: "Board and price region annotation for the fuel price detection pipeline.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" className="h-full antialiased">
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}
