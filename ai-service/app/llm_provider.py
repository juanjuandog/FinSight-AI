"""Config-driven text generation adapters for FinSight.

Cloud providers are deliberately optional: credentials are read only at request time
from the process environment and are never returned by the service.
"""

from dataclasses import dataclass
import os
from typing import Any

import requests


class ModelProviderError(RuntimeError):
    """Raised when the configured generation provider cannot be used."""


@dataclass(frozen=True)
class GenerationResult:
    content: str
    model: str
    source: str


SUPPORTED_PROVIDERS = {"ollama", "openai-compatible", "anthropic"}


def configured_provider() -> str:
    provider = env_value("LLM_PROVIDER", default="ollama").lower().replace("_", "-")
    if provider not in SUPPORTED_PROVIDERS:
        raise ModelProviderError(
            f"Unsupported LLM_PROVIDER '{provider}'. Supported values: "
            + ", ".join(sorted(SUPPORTED_PROVIDERS))
        )
    return provider


def provider_status() -> dict[str, Any]:
    """Return safe configuration metadata without exposing credentials."""
    try:
        provider = configured_provider()
        return {
            "provider": provider,
            "model": configured_model(provider),
            "configured": is_configured(provider),
            "supportedProviders": sorted(SUPPORTED_PROVIDERS),
        }
    except ModelProviderError as exc:
        return {
            "provider": "invalid",
            "model": None,
            "configured": False,
            "supportedProviders": sorted(SUPPORTED_PROVIDERS),
            "error": str(exc),
        }


def configured_model(provider: str | None = None) -> str:
    provider = provider or configured_provider()
    if provider == "ollama":
        return env_value("LLM_MODEL", "OLLAMA_MODEL", "FINSIGHT_OLLAMA_MODEL", default="qwen2.5:7b")
    if provider == "openai-compatible":
        return env_value("LLM_MODEL", "OPENAI_COMPATIBLE_MODEL", default="gpt-4o-mini")
    return env_value("LLM_MODEL", "ANTHROPIC_MODEL", default="claude-3-5-haiku-latest")


def is_configured(provider: str | None = None) -> bool:
    provider = provider or configured_provider()
    if provider == "ollama":
        return bool(ollama_base_url())
    if provider == "openai-compatible":
        return bool(openai_compatible_base_url() and os.getenv("OPENAI_COMPATIBLE_API_KEY", "").strip())
    return bool(os.getenv("ANTHROPIC_API_KEY", "").strip())


def generate_json(system_prompt: str, user_prompt: str) -> GenerationResult:
    provider = configured_provider()
    if not is_configured(provider):
        raise ModelProviderError(f"{provider} is not configured")
    if provider == "ollama":
        return _generate_with_ollama(system_prompt, user_prompt)
    if provider == "openai-compatible":
        return _generate_with_openai_compatible(system_prompt, user_prompt)
    return _generate_with_anthropic(system_prompt, user_prompt)


def _generate_with_ollama(system_prompt: str, user_prompt: str) -> GenerationResult:
    response = requests.post(
        trim_trailing_slash(ollama_base_url()) + "/api/chat",
        json={
            "model": configured_model("ollama"),
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "stream": False,
            "format": "json",
            "options": {"temperature": 0.2, "top_p": 0.9},
        },
        timeout=timeout_seconds(),
    )
    response.raise_for_status()
    content = response.json().get("message", {}).get("content", "")
    return GenerationResult(require_content(content, "Ollama"), configured_model("ollama"), "ollama")


def _generate_with_openai_compatible(system_prompt: str, user_prompt: str) -> GenerationResult:
    response = requests.post(
        trim_trailing_slash(openai_compatible_base_url()) + "/chat/completions",
        headers={"Authorization": f"Bearer {os.environ['OPENAI_COMPATIBLE_API_KEY']}"},
        json={
            "model": configured_model("openai-compatible"),
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": 0.2,
            "response_format": {"type": "json_object"},
        },
        timeout=timeout_seconds(),
    )
    response.raise_for_status()
    choices = response.json().get("choices", [])
    content = choices[0].get("message", {}).get("content", "") if choices else ""
    return GenerationResult(
        require_content(content, "OpenAI-compatible provider"),
        configured_model("openai-compatible"),
        "openai-compatible",
    )


def _generate_with_anthropic(system_prompt: str, user_prompt: str) -> GenerationResult:
    response = requests.post(
        trim_trailing_slash(anthropic_base_url()) + "/messages",
        headers={
            "x-api-key": os.environ["ANTHROPIC_API_KEY"],
            "anthropic-version": "2023-06-01",
        },
        json={
            "model": configured_model("anthropic"),
            "max_tokens": 1200,
            "temperature": 0.2,
            "system": system_prompt + " Return a JSON object only.",
            "messages": [{"role": "user", "content": user_prompt}],
        },
        timeout=timeout_seconds(),
    )
    response.raise_for_status()
    blocks = response.json().get("content", [])
    content = next((block.get("text", "") for block in blocks if block.get("type") == "text"), "")
    return GenerationResult(require_content(content, "Anthropic"), configured_model("anthropic"), "anthropic")


def ollama_base_url() -> str:
    return env_value("OLLAMA_BASE_URL", default="http://localhost:11434")


def openai_compatible_base_url() -> str:
    return env_value("OPENAI_COMPATIBLE_BASE_URL", default="https://api.openai.com/v1")


def anthropic_base_url() -> str:
    return env_value("ANTHROPIC_BASE_URL", default="https://api.anthropic.com/v1")


def timeout_seconds() -> float:
    return float(env_value("LLM_TIMEOUT_SECONDS", "OLLAMA_TIMEOUT_SECONDS", default="45"))


def env_value(*names: str, default: str) -> str:
    """Return the first non-empty environment value, preserving Compose defaults."""
    for name in names:
        value = os.getenv(name, "").strip()
        if value:
            return value
    return default


def trim_trailing_slash(value: str) -> str:
    return value.rstrip("/")


def require_content(value: Any, provider: str) -> str:
    content = str(value).strip() if value is not None else ""
    if not content:
        raise ModelProviderError(f"{provider} response did not contain content")
    return content
