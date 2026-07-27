from __future__ import annotations

from functools import lru_cache
import os
from typing import Sequence


EMBEDDING_MODEL = os.getenv(
    "EMBEDDING_MODEL",
    "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
)
RERANK_MODEL = os.getenv("RERANK_MODEL", "BAAI/bge-reranker-base")


def local_models_enabled() -> bool:
    return os.getenv("AI_LOCAL_MODELS_ENABLED", "true").lower() in {"1", "true", "yes"}


@lru_cache(maxsize=1)
def embedding_model():
    if not local_models_enabled():
        return None
    from sentence_transformers import SentenceTransformer

    return SentenceTransformer(EMBEDDING_MODEL)


@lru_cache(maxsize=1)
def rerank_model():
    if not local_models_enabled():
        return None
    from sentence_transformers import CrossEncoder

    return CrossEncoder(RERANK_MODEL)


def embed_texts(texts: Sequence[str]) -> list[list[float]] | None:
    model = embedding_model()
    if model is None:
        return None
    vectors = model.encode(
        list(texts),
        batch_size=max(1, min(int(os.getenv("EMBEDDING_BATCH_SIZE", "32")), 128)),
        normalize_embeddings=True,
        show_progress_bar=False,
    )
    return [[float(value) for value in vector] for vector in vectors]


def rerank_scores(question: str, passages: Sequence[str]) -> list[float] | None:
    model = rerank_model()
    if model is None:
        return None
    pairs = [(question, passage) for passage in passages]
    scores = model.predict(
        pairs,
        batch_size=max(1, min(int(os.getenv("RERANK_BATCH_SIZE", "16")), 64)),
        show_progress_bar=False,
    )
    return [float(score) for score in scores]


def runtime_status() -> dict[str, str | bool]:
    return {
        "localModelsEnabled": local_models_enabled(),
        "embeddingModel": EMBEDDING_MODEL,
        "rerankModel": RERANK_MODEL,
    }
