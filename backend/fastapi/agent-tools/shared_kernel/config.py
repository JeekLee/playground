"""M8 massing-gen runtime configuration per ADR-18 §19.

All knobs are env-overridable. Defaults match compose-internal hostnames
so the container boots without any operator wiring on a fresh stack.
"""

from __future__ import annotations

from functools import lru_cache
from typing import Optional

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=None, extra="ignore")

    # ADR-20 §D4 — the architecture BC is now a stateless generator: no
    # arch.outputs store, no Postgres. The DB settings + db_url were retired
    # along with the persist node and shared_kernel.database.

    # --- Cross-BC HTTP (docs-api body fetch per ADR-08 Exception 5) ---
    docs_api_base_url: str = Field(default="http://docs-api:18082")
    docs_api_timeout_seconds: float = Field(default=10.0)

    # --- LLM (spark-inference-gateway OpenAI-compatible) ---
    spring_ai_openai_base_url: str = Field(
        default="http://spark-inference-gateway:8000",
        alias="SPRING_AI_OPENAI_BASE_URL",
    )
    spring_ai_openai_api_key: str = Field(
        default="dummy-not-used",
        alias="SPRING_AI_OPENAI_API_KEY",
    )
    # qwen3-vl-30b-a3b is the model the gateway actually serves — the
    # text-only qwen3-30b-a3b was retired in the M6 swap (see infra
    # .env.example SPRING_AI_CHAT_MODEL). The stale default 404'd here.
    llm_model: str = Field(default="qwen3-vl-30b-a3b", alias="PLAYGROUND_MASSING_GEN_LLM_MODEL")
    llm_timeout_seconds: float = Field(default=120.0)
    llm_max_tokens: Optional[int] = Field(default=None)
    llm_temperature: float = Field(default=0.1)

    # --- Algorithm knobs (ADR-18 §8) ---
    default_floor_height_m: float = Field(default=3.5)
    default_max_floors: int = Field(
        default=10,
        alias="PLAYGROUND_MASSING_GEN_MAX_FLOORS",
    )

    # --- Resolve defaults (ADR-19 Phase 3a — BriefAnalysis -> MassingInputs) ---
    # Applied only when the brief does not state the value.
    default_coverage_cap: float = Field(
        default=0.6,
        alias="PLAYGROUND_ARCHITECTURE_DEFAULT_COVERAGE_CAP",
    )
    default_target_floors_above: int = Field(
        default=4,
        alias="PLAYGROUND_ARCHITECTURE_DEFAULT_TARGET_FLOORS_ABOVE",
    )

    # --- MinIO (ADR-20 §D3 revised — architecture BC owns write path) ---
    minio_endpoint: str = Field(
        default="http://minio-playground:9000",
        alias="PLAYGROUND_ARCHITECTURE_MINIO_ENDPOINT",
    )
    minio_access_key: str = Field(default="playground", alias="MINIO_ROOT_USER")
    minio_secret_key: str = Field(default="playground-secret", alias="MINIO_ROOT_PASSWORD")
    minio_bucket: str = Field(
        default="rag-chat-attachments",
        alias="PLAYGROUND_ARCHITECTURE_MINIO_BUCKET",
    )

    # --- Server ---
    port: int = Field(default=18083, alias="PLAYGROUND_MASSING_GEN_PORT")


@lru_cache
def get_settings() -> Settings:
    return Settings()
