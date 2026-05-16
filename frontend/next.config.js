/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  output: 'standalone',
  poweredByHeader: false,
  experimental: {
    // Keep client bundle lean: no unused features enabled here.
  },
};

module.exports = nextConfig;
