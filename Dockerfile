# Build stage
FROM node:22-slim AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .

# AUTH_SECRET is only needed at runtime; a placeholder keeps the build quiet.
ENV AUTH_SECRET=build-time-placeholder
RUN npm run build


# Runtime stage — `output: 'standalone'` ships only what the server needs.
FROM node:22-slim AS runtime

WORKDIR /app

ENV NODE_ENV=production
ENV PORT=3000

COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public

EXPOSE 3000

CMD ["node", "server.js"]
