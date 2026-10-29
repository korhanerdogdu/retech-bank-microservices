# risk-dashboard/Dockerfile — build context is the repository root, because
# the WebAssembly module is compiled from ../risk-engine/risk-wasm.

FROM node:22-bookworm AS build
RUN curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --target wasm32-unknown-unknown
ENV PATH="/root/.cargo/bin:${PATH}"
WORKDIR /repo
COPY risk-engine ./risk-engine
COPY risk-dashboard/package.json risk-dashboard/package-lock.json ./risk-dashboard/
RUN cd risk-dashboard && npm ci
COPY risk-dashboard ./risk-dashboard
RUN cd risk-dashboard && npm run build

FROM nginx:1.27-alpine
COPY --from=build /repo/risk-dashboard/dist /usr/share/nginx/html
EXPOSE 80
