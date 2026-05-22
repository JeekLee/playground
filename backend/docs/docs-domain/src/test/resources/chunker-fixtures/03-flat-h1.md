# Introduction

This section covers the basics of the playground system.
The system is composed of multiple bounded contexts that communicate via Kafka.

# Architecture

The gateway routes requests to individual services using Spring Cloud Gateway.
Each service is a Spring Boot application bound to its own port per ADR-07.

# Data Stores

Postgres is the primary persistence layer, with one schema per bounded context.
pgvector is used for storing dense embeddings in the rag-ingestion BC.
