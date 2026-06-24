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
    && for attempt in 1 2 3; do \
        pnpm install --frozen-lockfile --ignore-scripts && break; \
        if [ "${attempt}" = "3" ]; then exit 1; fi; \
        sleep 5; \
    done
COPY services/frontend/fides ./
ARG NEXT_PUBLIC_FIDES_OTP_ADAPTER=real
ARG NEXT_PUBLIC_FIDES_BFF_BASE_URL=/api/v1
ENV CI=true
ENV PNPM_CONFIG_IGNORE_SCRIPTS=true
ENV NEXT_PUBLIC_FIDES_OTP_ADAPTER=${NEXT_PUBLIC_FIDES_OTP_ADAPTER}
ENV NEXT_PUBLIC_FIDES_BFF_BASE_URL=${NEXT_PUBLIC_FIDES_BFF_BASE_URL}
RUN mkdir -p public && pnpm build
RUN patched_picomatch="$(find node_modules/.pnpm -path '*/node_modules/picomatch/package.json' -print -quit)" \
    && test -n "${patched_picomatch}" \
    && patched_picomatch_dir="$(dirname "${patched_picomatch}")" \
    && grep -q '"version": "4.0.4"' "${patched_picomatch}" \
    && find .next/standalone -path '*/next/dist/compiled/picomatch' -type d -exec sh -c 'rm -rf "$1" && mkdir -p "$1" && cp -R "$2"/. "$1"/' sh {} "${patched_picomatch_dir}" \;

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
RUN addgroup -S nextjs && adduser -S nextjs -G nextjs
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/bin/npm /usr/local/bin/npx
RUN mkdir -p ./public
COPY --from=build /workspace/services/frontend/fides/public ./public
COPY --from=build --chown=nextjs:nextjs /workspace/services/frontend/fides/.next/standalone ./
COPY --from=build --chown=nextjs:nextjs /workspace/services/frontend/fides/.next/static ./.next/static
USER nextjs
EXPOSE 3000
CMD ["node", "server.js"]
