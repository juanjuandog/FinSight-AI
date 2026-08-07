import os
import unittest
from unittest.mock import Mock, patch

from app.llm_provider import ModelProviderError, configured_model, generate_json, provider_status


class ModelProviderTest(unittest.TestCase):
    def test_openai_compatible_requires_a_key_before_making_a_request(self):
        with patch.dict(os.environ, {"LLM_PROVIDER": "openai-compatible"}, clear=True):
            with patch("app.llm_provider.requests.post") as request:
                with self.assertRaisesRegex(ModelProviderError, "not configured"):
                    generate_json("system", "user")

        request.assert_not_called()

    def test_openai_compatible_uses_standard_chat_completions_shape(self):
        response = Mock()
        response.json.return_value = {"choices": [{"message": {"content": '{"rating": "中性"}'}}]}
        with patch.dict(
            os.environ,
            {
                "LLM_PROVIDER": "openai-compatible",
                "OPENAI_COMPATIBLE_API_KEY": "test-key",
                "OPENAI_COMPATIBLE_BASE_URL": "https://example.test/v1/",
                "OPENAI_COMPATIBLE_MODEL": "demo-model",
            },
            clear=True,
        ):
            with patch("app.llm_provider.requests.post", return_value=response) as request:
                result = generate_json("system", "user")

        self.assertEqual(result.source, "openai-compatible")
        self.assertEqual(result.model, "demo-model")
        self.assertEqual(result.content, '{"rating": "中性"}')
        self.assertEqual(request.call_args.args[0], "https://example.test/v1/chat/completions")
        self.assertEqual(request.call_args.kwargs["headers"]["Authorization"], "Bearer test-key")
        self.assertEqual(request.call_args.kwargs["json"]["response_format"], {"type": "json_object"})

    def test_health_status_never_exposes_a_provider_key(self):
        with patch.dict(
            os.environ,
            {"LLM_PROVIDER": "anthropic", "ANTHROPIC_API_KEY": "not-for-output"},
            clear=True,
        ):
            status = provider_status()

        self.assertEqual(status["provider"], "anthropic")
        self.assertTrue(status["configured"])
        self.assertNotIn("not-for-output", str(status))

    def test_blank_unified_model_override_keeps_the_provider_default(self):
        with patch.dict(
            os.environ,
            {"LLM_PROVIDER": "ollama", "LLM_MODEL": "", "OLLAMA_MODEL": "qwen2.5:7b"},
            clear=True,
        ):
            self.assertEqual(configured_model(), "qwen2.5:7b")

    def test_anthropic_uses_messages_api_shape(self):
        response = Mock()
        response.json.return_value = {"content": [{"type": "text", "text": '{"rating": "积极"}'}]}
        with patch.dict(
            os.environ,
            {
                "LLM_PROVIDER": "anthropic",
                "ANTHROPIC_API_KEY": "test-key",
                "ANTHROPIC_BASE_URL": "https://example.test/v1/",
                "ANTHROPIC_MODEL": "demo-claude",
            },
            clear=True,
        ):
            with patch("app.llm_provider.requests.post", return_value=response) as request:
                result = generate_json("system", "user")

        self.assertEqual(result.source, "anthropic")
        self.assertEqual(result.model, "demo-claude")
        self.assertEqual(request.call_args.args[0], "https://example.test/v1/messages")
        self.assertEqual(request.call_args.kwargs["headers"]["x-api-key"], "test-key")
        self.assertEqual(request.call_args.kwargs["json"]["messages"], [{"role": "user", "content": "user"}])
