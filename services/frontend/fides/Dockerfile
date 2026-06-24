# syntax=docker/dockerfile:1.7
FROM node:22-alpine AS build
WORKDIR /workspace/services/frontend/fides
RUN apk add --no-cache git
RUN corepack enable && corepack prepare pnpm@11.8.0 --activate
COPY services/frontend/fides/package.json services/frontend/fides/pnpm-lock.yaml services/frontend/fides/pnpm-workspace.yaml ./
RUN --mount=type=secret,id=github_token \
    token="$(cat /run/secrets/github_token)" \
    && export GIT_CONFIG_COUNT=2 \
    && export GIT_CONFIG_KEY_0="url.https://x-access-token:${token}@github.com/.insteadOf" \
    && export GIT_CONFIG_VALUE_0="git@github.com:" \
    && export GIT_CONFIG_KEY_1="url.https://x-access-token:${token}@github.com/.insteadOf" \
    && export GIT_CONFIG_VALUE_1="ssh://git@github.com/" \
    && pnpm install --frozen-lockfile --ignore-scripts
COPY services/frontend/fides ./
ARG NEXT_PUBLIC_FIDES_OTP_ADAPTER=real
ARG NEXT_PUBLIC_FIDES_BFF_BASE_URL=/api/v1
ENV CI=true
ENV PNPM_CONFIG_IGNORE_SCRIPTS=true
ENV NEXT_PUBLIC_FIDES_OTP_ADAPTER=${NEXT_PUBLIC_FIDES_OTP_ADAPTER}
ENV NEXT_PUBLIC_FIDES_BFF_BASE_URL=${NEXT_PUBLIC_FIDES_BFF_BASE_URL}
RUN mkdir -p public && pnpm build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
RUN addgroup -S nextjs && adduser -S nextjs -G nextjs
RUN mkdir -p ./public
COPY --from=build /workspace/services/frontend/fides/public ./public
COPY --from=build --chown=nextjs:nextjs /workspace/services/frontend/fides/.next/standalone ./
COPY --from=build --chown=nextjs:nextjs /workspace/services/frontend/fides/.next/static ./.next/static
USER nextjs
EXPOSE 3000
CMD ["node", "server.js"]
