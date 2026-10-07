FROM public.ecr.aws/docker/library/node:22.23.2-alpine3.24 AS builder
WORKDIR /app

COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci

COPY . .
RUN npm run build

FROM public.ecr.aws/docker/library/nginx:1.30.5-alpine3.24
ENV AGENT_API_UPSTREAM=host.docker.internal:8080
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80
