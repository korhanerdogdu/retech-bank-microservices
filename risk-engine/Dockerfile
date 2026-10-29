# risk-engine/Dockerfile — native Rust Fraud Risk Engine (Axum + Tokio)

FROM rust:1-slim-bookworm AS build
WORKDIR /src
COPY . .
RUN cargo build --release --locked -p risk-engine

FROM gcr.io/distroless/cc-debian12
COPY --from=build /src/target/release/risk-engine /usr/local/bin/risk-engine
ENV PORT=8086
EXPOSE 8086
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD ["/usr/local/bin/risk-engine", "healthcheck"]
ENTRYPOINT ["/usr/local/bin/risk-engine"]
