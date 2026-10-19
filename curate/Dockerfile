# curate for the review on the server (compose.review.yaml), as the site's frontend is built (frontend/Dockerfile).
FROM node:24-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:24-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
# public/ is optional in the source tree, but the runtime stage copies it.
RUN mkdir -p public && npm run build

FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3100 \
    HOSTNAME=0.0.0.0 \
    LIMINIS_SECRETS_DIR=/secrets
# The user id of the workbench's and auth's images (backend/Dockerfile): curate reads the client secret auth writes to
# their shared volume, and keeps its cookie key there.
RUN adduser -D -u 10001 -h /app app && mkdir /secrets && chown app /secrets
COPY --from=build --chown=app /app/public ./public
COPY --from=build --chown=app /app/.next/standalone ./
COPY --from=build --chown=app /app/.next/static ./.next/static
USER app
EXPOSE 3100
CMD ["node", "server.js"]
