import unittest
import os
from unittest.mock import patch

from app.main import StockAnalysisRequest, analyze_stock


class StockAnalysisTest(unittest.TestCase):
    def test_analyze_stock_fallback_keeps_persisted_model_name_bounded(self):
        request = StockAnalysisRequest(
            company={
                "symbol": "600519",
                "name": "贵州茅台",
                "exchange": "SH",
                "industry": "白酒",
            },
            quote={"changePercent": -1.2, "realtime": False},
            metrics=[{"code": "ROE", "value": "0.15"}],
            risks=[],
            evidence=[{"title": "年度报告", "text": "经营稳健"}],
        )
        long_error = "connection refused: " + "x" * 240

        with patch("app.main.call_configured_stock_analysis", side_effect=RuntimeError(long_error)):
            response = analyze_stock(request)

        self.assertEqual(response.source, "fallback-rule")
        self.assertEqual(response.model, "rule-fallback")
        self.assertLessEqual(len(response.model), 128)
        self.assertFalse(response.aiGenerated)

    def test_unconfigured_cloud_provider_keeps_stock_analysis_on_rule_fallback(self):
        request = StockAnalysisRequest(company={"name": "贵州茅台"})
        with patch.dict(os.environ, {"LLM_PROVIDER": "openai-compatible"}, clear=True):
            with patch("app.llm_provider.requests.post") as remote_request:
                response = analyze_stock(request)

        self.assertEqual(response.source, "fallback-rule")
        self.assertFalse(response.aiGenerated)
        remote_request.assert_not_called()
