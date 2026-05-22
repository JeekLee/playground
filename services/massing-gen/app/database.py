"""SQLAlchemy engine + session factory.

Single sync engine; uvicorn workers default to 1 per ADR-18 §A18.3 so we
don't need async DB. If we ever scale to N>1 workers, each gets its own
engine + connection pool — psycopg manages the pool per process.
"""

from __future__ import annotations

from contextlib import contextmanager
from collections.abc import Iterator
from functools import lru_cache

from sqlalchemy import create_engine
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker

from .config import get_settings


@lru_cache
def get_engine() -> Engine:
    settings = get_settings()
    return create_engine(
        settings.db_url,
        pool_size=5,
        max_overflow=5,
        pool_pre_ping=True,
        future=True,
    )


@lru_cache
def get_session_factory() -> sessionmaker[Session]:
    return sessionmaker(bind=get_engine(), expire_on_commit=False, future=True)


@contextmanager
def session_scope() -> Iterator[Session]:
    factory = get_session_factory()
    session = factory()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()
