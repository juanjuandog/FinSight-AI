# FinSight AI Service

The AI sidecar exposes embedding, reranking, document parsing, and stock-analysis
endpoints on port `8001`.

## Run locally

```bash
cd ai-service
python -m pip install -r requirements.txt
uvicorn app.main:app --reload --port 8001
```

Check that the sidecar is ready before enabling it in the backend:

```bash
curl http://localhost:8001/health
```

The health response reports the configured provider and runtime status without
returning API credentials. If no model is available, the stock-analysis endpoint
keeps its deterministic fallback.
