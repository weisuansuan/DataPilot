# AI Text2SQL (Stage 4)

## Provider and client choice

The configured provider is Qiniu AI's OpenAI-compatible Chat Completions endpoint. Defaults are `https://api.qnaigc.com/v1`, model `qiniu/conatus-free`, `stream=true`, a 45-second request deadline, and at most 1,200 output tokens. No `response_format`, tool calls, or Qiniu-only request parameters are sent.

Spring AI 2.0.2 was reviewed: its OpenAI-compatible client supports a custom base URL, max tokens, synchronous chat, and reactive streaming. DataPilot uses a small Java 21 `HttpClient` behind the `ChatModelClient` interface instead, so it can bound and test the Qiniu SSE body, enforce the `[DONE]` terminator, stop on timeout, and avoid relying on optional provider extensions. Spring AI remains available for a future client implementation. No new model SDK dependency was added.

The backend reads `QINIU_API_KEY` from the process environment. It is never sent to Vue, written to a response, or included in application logs. If the key is absent, AI calls return `AI_NOT_CONFIGURED`; deterministic mock clients exercise the same orchestration in automated tests. Restart the backend process after changing Windows environment variables.

Configuration overrides:

| Environment variable | Default | Purpose |
|---|---|---|
| `DATAPILOT_AI_PROVIDER` | `qiniu` | Response/provider label |
| `DATAPILOT_AI_BASE_URL` | `https://api.qnaigc.com/v1` | OpenAI-compatible API base |
| `DATAPILOT_AI_MODEL` | `qiniu/conatus-free` | Model name |
| `QINIU_API_KEY` | empty | Backend-only bearer credential |
| `DATAPILOT_AI_STREAM` | `true` | Use SSE; `false` uses a normal JSON response |
| `DATAPILOT_AI_TIMEOUT_SECONDS` | `45` | Request and response-body deadline (1–90 seconds) |
| `DATAPILOT_AI_MAX_OUTPUT_TOKENS` | `1200` | Request output cap (128–4096) |
| `DATAPILOT_AI_MAX_PROMPT_CHARS` | `12000` | System-context character cap |
| `DATAPILOT_AI_MAX_REPAIR_ATTEMPTS` | `2` | Maximum repairs after the initial generation (0–2) |
| `DATAPILOT_AI_MAX_CONCURRENCY` | `2` | Concurrent model requests (1–4) |
| `DATAPILOT_AI_MAX_PROVIDER_RETRIES` | `1` | Bounded retries for recognized request-rate 429 responses (0–2) |
| `DATAPILOT_AI_MAX_RETRY_DELAY_SECONDS` | `5` | Maximum backoff before a provider retry (0–30 seconds) |

## Request flow

1. `Text2SqlPromptBuilder` loads `text2sql-v1.txt` and injects the current `SqlPolicy` allowlisted field map and the nine metric definitions. It includes approved foreign-key relationships and explicit payment/refund fanout guidance. The user's question is passed separately as untrusted user content; no secret or database credential enters the prompt.
2. `OpenAiCompatibleChatClient` sends one Chat Completions request. SSE `data:` frames are accumulated until `[DONE]`; JSON parsing and SQL handling begin only after complete content. Response bytes and output characters are capped. Token usage is optional because the provider may omit it.
3. `Text2SqlOutputParser` accepts a JSON object, validates required fields, handles clarification and unsupported questions, and does not assume server-enforced JSON mode.
4. Every generated SQL statement passes the existing `SqlPolicy`. Only after authorization can `QueryService` run EXPLAIN cost review, acquire query concurrency, and execute through `datapilot_reader` under the existing deadline and 200-row cap.

The model is never trusted to authorize its own output. The prompt is a usability aid; `SqlPolicy` and database privileges remain authoritative.

## Repairs and errors

At most two additional model requests are made for parser errors or safe, generic MySQL syntax/schema execution errors. Each repaired SQL string is parsed and checked again before it can execute. Only a sanitized category is added to the system prompt; raw JDBC exceptions and server details are not sent to the model.

No repair is attempted for `UNAUTHORIZED_TABLE`, `UNAUTHORIZED_COLUMN`, `UNAUTHORIZED_FUNCTION`, other SQL policy denials, plan-cost rejections, timeouts, busy queries, authentication failures, rate limits, invalid JSON, empty output, or provider errors. These return a structured error.

Provider HTTP 429 responses are parsed only for a bounded JSON error code/type and an allowlist of `Retry-After` and remaining request/token headers. Raw provider bodies and arbitrary headers are not logged or returned. Categories include request rate, quota/billing, model capacity, provider global limit, and unknown. Only recognized request-rate errors may receive the configured finite retry count; `Retry-After` is respected only within the configured wait cap. Backoff uses exponential delay and jitter. After a final 429, an in-memory cooldown prevents repeated calls (300 seconds for quota/global limits, 30 seconds for capacity, 5 seconds for request rate, and 60 seconds when unknown); a provider `Retry-After` value takes precedence. The API and UI return safe category/status/wait details. Cooldown is process-local and resets when the backend restarts.

On 2026-11-07 one Qiniu diagnostic request returned HTTP 429, provider type `rate_limit_exceeded_error`, code `free_campaign_global_limit`, and none of the inspected `Retry-After` or standard rate-limit headers. This establishes a provider global limit, but does not prove whether the underlying cause is request frequency, quota/campaign policy, or model capacity. This category is not retried.

The Stage 5 browser smoke test also received a model response that the strict Text2SQL parser could not accept. The API returned the controlled `AI_INVALID_RESPONSE` state and executed no SQL. No immediate retry was made. A separate SSE evaluation request succeeded end to end for Q01; online evidence is intentionally limited and is recorded in [text2sql-evaluation.md](text2sql-evaluation.md).

## API examples

Generate and inspect before execution:

```powershell
$body = @{ question = "比较 2026 年 9 月和 10 月各地区实付金额" } | ConvertTo-Json
$generated = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/ai/generate -ContentType 'application/json' -Body $body
$generated | Select-Object success, intent, sql, explanation, validation, model, modelResponseTimeMs, tokenUsage
```

Run in one request:

```powershell
$body = @{ question = "列出 2026 年每月实付金额" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/v1/ai/query -ContentType 'application/json' -Body $body
```

`/ai/generate` does not execute SQL. A separate execution must still use `/api/v1/query/execute`, which independently repeats AST authorization. `/ai/query` is the one-click path and internally uses the same policy and query service.

Responses include intent, SQL and explanation, validation, provider/model, prompt version, model time, repair count, optional token usage, and (for `/ai/query`) columns/types, rows, query time, row count, truncation, and controlled error details.

The query API serializes DECIMAL and BIGINT values as decimal strings so browser JSON parsing cannot silently round monetary values or large integers. The column metadata retains the original MySQL type; chart recommendations convert only values within JavaScript's safe numeric range.

## Evaluation corpus

`backend/src/main/resources/text2sql/evaluation-v1.json` contains 20 Chinese business questions with metric IDs, reference SQL, output columns, and canonical values for core totals. `Text2SqlEvaluationCorpusTest` checks uniqueness and that every reference query passes the same SQL policy. `Text2SqlEvaluationDatabaseIT` runs all 20 reference queries against the real seeded MySQL database and verifies output shape plus exact canonical totals for orders, paid orders, paid principal, refunds, and net paid principal.

This is a reference-query corpus and database-semantic check, not a claim that a live model has achieved 20/20 semantic accuracy. See [text2sql-evaluation.md](text2sql-evaluation.md) for bounded offline/live runs and score definitions. Mock client and orchestration tests are repeatable and do not incur model charges.

AI query results can be charted and summarized deterministically using the executed rows; see [ai-visualization.md](ai-visualization.md).
