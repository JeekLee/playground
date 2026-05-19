# Services

Top-level content about the services layer.

## Gateway

The gateway handles OAuth 2.0 flows and injects X-User-* headers downstream.

### Route Configuration

Routes are defined in application.yml and load-balanced via Spring Cloud Gateway.

## Identity

The identity BC handles user registration and token refresh.

### OAuth Flow

The OAuth callback filter extracts the authorization code and exchanges it for tokens.

### Session Management

Refresh tokens are stored in Redis with a TTL matching the provider's expiry.

# Infrastructure

## Kafka

Topics follow the naming convention: `<bc>.<aggregate>.<verb-past>`.

## Postgres

Each bounded context owns its Flyway migration history in a dedicated schema.
