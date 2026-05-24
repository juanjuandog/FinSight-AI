from datetime import datetime, timezone
from fastapi import FastAPI, Query
from pydantic import BaseModel, Field
from typing import Any
import hashlib
import json
import math
import os
import re
import time

app = FastAPI(title="FinSight AI Service", version="0.1.0")
_stock_universe_cache: dict[str, Any] = {"expiresAt": 0.0, "payload": None}


class EvidenceChunk(BaseModel):
    documentId: str
    title: str
    documentType: str
    publishedAt: str | None = None
    section: str
    text: str
    score: float


class RerankRequest(BaseModel):
    question: str
    candidates: list[EvidenceChunk]


class RerankResponse(BaseModel):
    evidence: list[EvidenceChunk]


class GenerateAnswerRequest(BaseModel):
    question: str
    structuredQuery: dict[str, Any] = Field(default_factory=dict)
    evidence: list[EvidenceChunk]


class GenerateAnswerResponse(BaseModel):
    answer: str


class EmbedRequest(BaseModel):
    texts: list[str]
    dimension: int = Field(default=384, ge=16, le=1536)


class EmbedResponse(BaseModel):
    embeddings: list[list[float]]
    model: str
    dimension: int


class ParseDocumentRequest(BaseModel):
    documentId: str
    content: str


class ParsedSection(BaseModel):
    section: str
    text: str
    tags: list[str] = Field(default_factory=list)


class ParseDocumentResponse(BaseModel):
    documentId: str
    sections: list[ParsedSection]


class StockInfo(BaseModel):
    symbol: str
    name: str
    exchange: str
    industry: str


class AShareUniverseResponse(BaseModel):
    source: str
    total: int
    stocks: list[StockInfo]
    syncedAt: str
    completed: bool
    error: str | None = None


class StockAnalysisRequest(BaseModel):
    company: dict[str, Any] = Field(default_factory=dict)
    quote: dict[str, Any] = Field(default_factory=dict)
    metrics: list[dict[str, Any]] = Field(default_factory=list)
    risks: list[dict[str, Any]] = Field(default_factory=list)
    evidence: list[dict[str, Any]] = Field(default_factory=list)


class StockAnalysisResponse(BaseModel):
    rating: str
    summary: str
    positivePoints: list[str] = Field(default_factory=list)
    riskPoints: list[str] = Field(default_factory=list)
    confidence: int = Field(default=70, ge=0, le=100)
    citations: list[str] = Field(default_factory=list)
    model: str
    source: str
    aiGenerated: bool


@app.get("/health")
def health() -> dict[str, str]:
    return {
        "status": "ok",
        "embeddingModel": "hashing-ngram-384",
        "ollamaModel": ollama_model(),
    }


@app.get("/stocks/a-shares", response_model=AShareUniverseResponse)
def a_share_universe(
    refresh: bool = False,
    limit: int = Query(default=0, ge=0, le=10000),
) -> AShareUniverseResponse:
    cached = _stock_universe_cache["payload"]
    if not refresh and cached is not None and time.time() < _stock_universe_cache["expiresAt"]:
        return trim_universe(cached, limit)

    response = load_a_share_universe()
    if response.completed:
        _stock_universe_cache["payload"] = response
        _stock_universe_cache["expiresAt"] = time.time() + 900
    return trim_universe(response, limit)


@app.post("/analyze-stock", response_model=StockAnalysisResponse)
def analyze_stock(request: StockAnalysisRequest) -> StockAnalysisResponse:
    fallback = fallback_stock_analysis(request)
    try:
        return call_ollama_stock_analysis(request, fallback)
    except Exception:  # noqa: BLE001 - local demos should keep working without Ollama.
        return fallback.model_copy(update={
            "source": "fallback-rule",
            "model": fallback.model,
            "aiGenerated": False,
        })


@app.post("/embed", response_model=EmbedResponse)
def embed(request: EmbedRequest) -> EmbedResponse:
    embeddings = [hashing_embedding(text, request.dimension) for text in request.texts]
    return EmbedResponse(
        embeddings=embeddings,
        model=f"hashing-ngram-{request.dimension}",
        dimension=request.dimension,
    )


@app.post("/rerank", response_model=RerankResponse)
def rerank(request: RerankRequest) -> RerankResponse:
    question_terms = tokenize(request.question)

    def score(chunk: EvidenceChunk) -> float:
        text_terms = tokenize(chunk.title + " " + chunk.section + " " + chunk.text)
        overlap = len(question_terms & text_terms)
        return chunk.score + overlap * 0.03

    ranked = sorted(
        (chunk.model_copy(update={"score": score(chunk)}) for chunk in request.candidates),
        key=lambda chunk: chunk.score,
        reverse=True,
    )
    return RerankResponse(evidence=ranked)


@app.post("/generate-answer", response_model=GenerateAnswerResponse)
def generate_answer(request: GenerateAnswerRequest) -> GenerateAnswerResponse:
    evidence_text = " ".join(chunk.text for chunk in request.evidence[:3])
    citations = "、".join(
        f"{chunk.title}/{chunk.section}" for chunk in request.evidence[:3]
    )
    intent = request.structuredQuery.get("intent", "FINANCIAL_QA")
    answer = (
        f"分析意图：{intent}。基于证据可见，{evidence_text} "
        f"引用来源：{citations}。本结果只做信息整理和风险提示，不构成投资建议。"
    )
    return GenerateAnswerResponse(answer=answer)


@app.post("/parse-document", response_model=ParseDocumentResponse)
def parse_document(request: ParseDocumentRequest) -> ParseDocumentResponse:
    parts = [part.strip() for part in request.content.split("。") if part.strip()]
    sections = [
        ParsedSection(section=f"section-{idx + 1}", text=part, tags=infer_tags(part))
        for idx, part in enumerate(parts[:12])
    ]
    return ParseDocumentResponse(documentId=request.documentId, sections=sections)


def load_a_share_universe() -> AShareUniverseResponse:
    errors: list[str] = []
    loaders = [
        ("akshare-stock-info-a-code-name", load_akshare_code_name),
        ("akshare-stock-zh-a-spot-em", load_akshare_spot_em),
    ]
    for source, loader in loaders:
        try:
            stocks = loader()
            if stocks:
                return AShareUniverseResponse(
                    source=source,
                    total=len(stocks),
                    stocks=stocks,
                    syncedAt=utc_now(),
                    completed=True,
                )
            errors.append(f"{source}: empty result")
        except Exception as exc:  # noqa: BLE001 - keep the public endpoint degraded instead of crashing.
            errors.append(f"{source}: {exc}")

    return AShareUniverseResponse(
        source="akshare",
        total=0,
        stocks=[],
        syncedAt=utc_now(),
        completed=False,
        error="; ".join(errors),
    )


def call_ollama_stock_analysis(
    request: StockAnalysisRequest,
    fallback: StockAnalysisResponse,
) -> StockAnalysisResponse:
    import requests

    payload = {
        "model": ollama_model(),
        "messages": [
            {
                "role": "system",
                "content": (
                    "你是谨慎的A股投研助手。只基于用户提供的数据分析，不编造数据；"
                    "输出必须是JSON，字段为rating, summary, positivePoints, riskPoints, confidence, citations。"
                    "rating只能是积极、中性、谨慎之一。confidence是0到100整数。"
                    "结尾不要给买卖建议，只做信息整理和风险提示。"
                ),
            },
            {"role": "user", "content": stock_analysis_prompt(request)},
        ],
        "stream": False,
        "format": "json",
        "options": {
            "temperature": 0.2,
            "top_p": 0.9,
        },
    }
    response = requests.post(
        trim_trailing_slash(ollama_base_url()) + "/api/chat",
        json=payload,
        timeout=float(os.getenv("OLLAMA_TIMEOUT_SECONDS", "45")),
    )
    response.raise_for_status()
    content = response.json().get("message", {}).get("content", "")
    data = extract_json_object(content)
    return normalize_ai_analysis(data, fallback)


def stock_analysis_prompt(request: StockAnalysisRequest) -> str:
    context = {
        "company": request.company,
        "quote": request.quote,
        "metrics": request.metrics[:24],
        "risks": request.risks[:12],
        "evidence": request.evidence[:8],
    }
    return (
        "请基于以下A股公司上下文生成结构化投研分析JSON。"
        "positivePoints和riskPoints分别给2到4条中文短句；"
        "citations从evidence标题或指标库中选择，最多5条。\n"
        + json.dumps(context, ensure_ascii=False, default=str)
    )


def normalize_ai_analysis(data: dict[str, Any], fallback: StockAnalysisResponse) -> StockAnalysisResponse:
    rating = normalize_rating(data.get("rating"), fallback.rating)
    summary = clean_text(data.get("summary")) or fallback.summary
    positive_points = normalize_text_list(data.get("positivePoints")) or fallback.positivePoints
    risk_points = normalize_text_list(data.get("riskPoints")) or fallback.riskPoints
    citations = normalize_text_list(data.get("citations")) or fallback.citations
    confidence = normalize_confidence(data.get("confidence"), fallback.confidence)
    return StockAnalysisResponse(
        rating=rating,
        summary=summary,
        positivePoints=positive_points[:4],
        riskPoints=risk_points[:4],
        confidence=confidence,
        citations=citations[:5],
        model=ollama_model(),
        source="ollama",
        aiGenerated=True,
    )


def fallback_stock_analysis(request: StockAnalysisRequest) -> StockAnalysisResponse:
    company_name = clean_text(request.company.get("name")) or "该公司"
    quote_realtime = bool(request.quote.get("realtime"))
    change_percent = safe_float(request.quote.get("changePercent"))
    risk_titles = [clean_text(item.get("title")) for item in request.risks if clean_text(item.get("title"))]
    metrics = {str(item.get("code")): safe_float(item.get("value")) for item in request.metrics}
    strong: list[str] = []
    weak: list[str] = []

    roe = metrics.get("ROE")
    if roe is not None:
        (strong if roe >= 0.10 else weak).append(f"ROE约为{roe:.2%}")
    revenue_yoy = metrics.get("REVENUE_YOY")
    if revenue_yoy is not None:
        (strong if revenue_yoy >= 0 else weak).append(f"营收同比约为{revenue_yoy:.2%}")
    ocf_ratio = metrics.get("OCF_NET_PROFIT")
    if ocf_ratio is not None:
        (strong if ocf_ratio >= 0.8 else weak).append(f"经营现金流/净利润约为{ocf_ratio:.2f}")
    if change_percent is not None and change_percent < -1:
        weak.append(f"短期行情涨跌幅约为{change_percent:.2f}%")

    warning_count = len(weak) + len(risk_titles)
    rating = "谨慎" if warning_count >= 3 else "中性" if warning_count >= 1 else "积极"
    confidence = max(62, min(88, 78 - warning_count * 4 + (4 if quote_realtime else 0)))
    quote_text = "实时行情已接入" if quote_realtime else "实时行情暂不可用，当前以本地数据为主"
    summary = (
        f"{company_name}当前评级为{rating}。{quote_text}；"
        f"系统结合财务指标、风险规则和公开证据生成该结论，仅作信息整理与风险提示。"
    )
    positives = strong[:3] or ["暂未看到特别突出的优势指标，需要结合后续财报继续观察"]
    risks = (weak + risk_titles)[:4] or ["暂未触发明显风险规则，但仍需关注后续公告、行业景气度和估值波动"]
    citations = [clean_text(item.get("title")) for item in request.evidence if clean_text(item.get("title"))]
    if request.metrics:
        citations.insert(0, "结构化财务指标")
    if request.risks:
        citations.insert(0, "财务风险信号")
    return StockAnalysisResponse(
        rating=rating,
        summary=summary,
        positivePoints=positives,
        riskPoints=risks,
        confidence=confidence,
        citations=dedupe(citations)[:5],
        model="rule-fallback",
        source="fallback-rule",
        aiGenerated=False,
    )


def extract_json_object(value: str) -> dict[str, Any]:
    try:
        parsed = json.loads(value)
        if isinstance(parsed, dict):
            return parsed
    except json.JSONDecodeError:
        pass
    match = re.search(r"\{.*\}", value, re.S)
    if not match:
        raise ValueError("Ollama response did not contain a JSON object")
    parsed = json.loads(match.group(0))
    if not isinstance(parsed, dict):
        raise ValueError("Ollama JSON response was not an object")
    return parsed


def normalize_rating(value: Any, fallback: str) -> str:
    text = clean_text(value) or fallback
    if "积极" in text or "看多" in text:
        return "积极"
    if "谨慎" in text or "风险" in text or "看空" in text:
        return "谨慎"
    return "中性"


def normalize_text_list(value: Any) -> list[str]:
    if isinstance(value, list):
        return [text for item in value if (text := clean_text(item))]
    if isinstance(value, str):
        parts = re.split(r"[；;\n]", value)
        return [part.strip(" -，,") for part in parts if part.strip(" -，,")]
    return []


def normalize_confidence(value: Any, fallback: int) -> int:
    number = safe_float(value)
    if number is None:
        return fallback
    if number <= 1:
        number *= 100
    return max(0, min(100, int(round(number))))


def safe_float(value: Any) -> float | None:
    if value is None:
        return None
    try:
        return float(str(value).replace("%", "").strip())
    except ValueError:
        return None


def dedupe(values: list[str | None]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        if not value or value in seen:
            continue
        result.append(value)
        seen.add(value)
    return result


def ollama_base_url() -> str:
    return os.getenv("OLLAMA_BASE_URL", "http://localhost:11434")


def ollama_model() -> str:
    return os.getenv("OLLAMA_MODEL", os.getenv("FINSIGHT_OLLAMA_MODEL", "qwen2.5:7b"))


def trim_trailing_slash(value: str) -> str:
    return value[:-1] if value.endswith("/") else value


def load_akshare_spot_em() -> list[StockInfo]:
    import akshare as ak

    frame = ak.stock_zh_a_spot_em()
    return stocks_from_records(frame.to_dict("records"), "沪深京A")


def load_akshare_code_name() -> list[StockInfo]:
    import akshare as ak

    frame = ak.stock_info_a_code_name()
    return stocks_from_records(frame.to_dict("records"), "A股")


def stocks_from_records(records: list[dict[str, Any]], fallback_industry: str) -> list[StockInfo]:
    stocks: dict[str, StockInfo] = {}
    for row in records:
        symbol = normalize_stock_code(first_present(row, "代码", "code", "symbol", "证券代码"))
        name = clean_text(first_present(row, "名称", "name", "shortName", "证券简称"))
        if not symbol or not name or not is_supported_a_stock_code(symbol):
            continue
        industry = clean_text(first_present(row, "行业", "所属行业", "板块", "securityTypeName"))
        stock = StockInfo(
            symbol=symbol,
            name=name,
            exchange=exchange_of(symbol),
            industry=industry or fallback_industry,
        )
        stocks[symbol] = stock
    return sorted(stocks.values(), key=lambda stock: stock.symbol)


def trim_universe(response: AShareUniverseResponse, limit: int) -> AShareUniverseResponse:
    if limit <= 0 or len(response.stocks) <= limit:
        return response
    return response.model_copy(update={"stocks": response.stocks[:limit]})


def first_present(row: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in row and row[key] is not None:
            return row[key]
    return None


def clean_text(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text or text.lower() == "nan" or text == "-":
        return None
    return text


def normalize_stock_code(value: Any) -> str | None:
    text = clean_text(value)
    if text is None:
        return None
    text = text.upper().removeprefix("SH").removeprefix("SZ").removeprefix("BJ")
    if text.endswith(".0"):
        text = text[:-2]
    if text.isdigit() and len(text) <= 6:
        return text.zfill(6)
    return None


def is_supported_a_stock_code(symbol: str) -> bool:
    return bool(
        re.fullmatch(r"\d{6}", symbol)
        and symbol.startswith(("60", "68", "69", "00", "30", "92", "8", "4"))
    )


def exchange_of(symbol: str) -> str:
    if symbol.startswith(("92", "8", "4")):
        return "BJ"
    if symbol.startswith(("6", "9")):
        return "SH"
    return "SZ"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def infer_tags(text: str) -> list[str]:
    tags: list[str] = []
    if any(keyword in text for keyword in ("营收", "净利润", "毛利率", "现金流")):
        tags.append("financial_metric")
    if any(keyword in text for keyword in ("风险", "不确定性", "压力")):
        tags.append("risk")
    if any(keyword in text for keyword in ("行业", "需求", "库存")):
        tags.append("industry")
    return tags


def tokenize(text: str) -> set[str]:
    normalized = text.lower()
    words = set(re.findall(r"[a-z0-9]+", normalized))
    chinese_chars = [char for char in normalized if "\u4e00" <= char <= "\u9fff"]
    grams = set(chinese_chars)
    grams.update(
        "".join(chinese_chars[index : index + 2])
        for index in range(max(0, len(chinese_chars) - 1))
    )
    return words | grams


def hashing_embedding(text: str, dimension: int) -> list[float]:
    vector = [0.0] * dimension
    for token in tokenize(text):
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        bucket = int.from_bytes(digest[:4], "big") % dimension
        sign = 1.0 if digest[4] % 2 == 0 else -1.0
        vector[bucket] += sign

    if not any(vector):
        digest = hashlib.sha256((text or "").encode("utf-8")).digest()
        for index in range(dimension):
            vector[index] = (digest[index % len(digest)] / 127.5) - 1.0

    norm = math.sqrt(sum(value * value for value in vector)) or 1.0
    return [round(value / norm, 6) for value in vector]
