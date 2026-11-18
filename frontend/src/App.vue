<script setup lang="ts">
import { defineAsyncComponent, onBeforeUnmount, onMounted, ref } from 'vue'
const DataVisualizations = defineAsyncComponent(() => import('./components/DataVisualizations.vue'))

interface RegionComparison {
  regionCode: string
  regionName: string
  septemberPaidOrders: number
  octoberPaidOrders: number
  septemberSales: number
  octoberSales: number
  salesChange: number
  salesChangePercent: number
}

interface CategoryRefundRate {
  categoryCode: string
  categoryName: string
  soldUnits: number
  refundedUnits: number
  refundRate: number
}

interface Overview {
  syntheticData: boolean
  datasetCode: string
  orderCount: number
  paidOrderCount: number
  paidSalesAmount: number
  completedRefundAmount: number
  refundObservationCutoff: string
  generatedAt: string
  regionComparisons: RegionComparison[]
  categoryRefundRates: CategoryRefundRate[]
}

type LoadState = 'loading' | 'ready' | 'error'
interface QueryColumn { name: string; type: string }
interface QueryResult { success: boolean; columns: QueryColumn[]; rows: Record<string, unknown>[]; rowCount: number; executionTimeMs: number; truncated: boolean; errorCode: string | null; message: string | null }
interface ValidationResult { allowed: boolean; errorCode: string | null; message: string | null }
interface ProviderFailure { category: string; status: number; retryAfterSeconds: number | null; attempts: number; remainingRequests: number | null; remainingTokens: number | null }
interface AiModelOption { id: string; provider: string; model: string; configured: boolean; available: boolean; defaultModel: boolean }
interface AiResponse { success: boolean; clarificationRequired: boolean; intent: string; sql: string; explanation: string; clarificationQuestion: string; validation: ValidationResult | null; queryResult: QueryResult | null; provider: string; model: string; promptVersion: string; modelResponseTimeMs: number; tokenUsage: { promptTokens: number | null; completionTokens: number | null; totalTokens: number | null } | null; repairAttempts: number; errorCode: string | null; message: string | null; providerFailure: ProviderFailure | null }

const sqlExamples = [
  { label: '地区月度实付', sql: "SELECT o.ship_region_name, DATE_FORMAT(p.paid_at, '%Y-%m') AS paid_month, SUM(p.amount) AS paid_amount FROM sales_order o JOIN payment p ON p.order_id = o.order_id WHERE p.status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED') AND p.paid_at IS NOT NULL GROUP BY o.ship_region_name, paid_month ORDER BY paid_month, paid_amount DESC" },
  { label: '品类销售分布', sql: "SELECT c.category_name, SUM(i.line_total) AS sales_amount FROM product_category c JOIN product p ON p.category_id = c.category_id JOIN order_item i ON i.product_id = p.product_id JOIN payment pay ON pay.order_id = i.order_id WHERE pay.status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED') GROUP BY c.category_name ORDER BY sales_amount DESC" },
  { label: '订单与客单价', sql: "SELECT p.status, COUNT(DISTINCT p.order_id) AS order_count, AVG(p.amount) AS average_paid_amount FROM payment p GROUP BY p.status ORDER BY order_count DESC" },
]
const sqlText = ref(sqlExamples[0]!.sql)
const queryBusy = ref(false)
const queryStatus = ref('')
const queryError = ref('')
const sqlQueryResult = ref<QueryResult | null>(null)
const aiQuestions = [
  '2026 年每个月的实付金额和已支付订单数是多少？',
  '比较 2026 年 9 月和 10 月各地区实付金额，哪个地区下降最多？',
  '各商品品类的实付销售金额从高到低怎么排？',
  '总实付金额、已完成退款金额和净支付金额分别是多少？',
  '按订单状态统计订单数。',
]
const aiQuestion = ref(aiQuestions[0]!)
const aiBusy = ref(false)
const aiError = ref('')
const aiStatus = ref('')
const aiResult = ref<AiResponse | null>(null)
const aiModels = ref<AiModelOption[]>([])
const selectedModelId = ref('')
const aiModelLoadError = ref('')
const aiRetryWait = ref(0)
let aiRetryTimer: number | undefined

const loadState = ref<LoadState>('loading')
const errorMessage = ref('')
const overview = ref<Overview | null>(null)

async function validateSql() {
  if (queryBusy.value) return
  queryBusy.value = true
  queryError.value = ''
  sqlQueryResult.value = null
  try {
    const response = await fetch('/api/v1/query/validate', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sql: sqlText.value }) })
    const result: ValidationResult = await response.json()
    queryStatus.value = result.allowed ? 'SQL 安全校验通过' : ''
    if (!result.allowed) queryError.value = result.message ?? 'SQL 未通过安全校验。'
    return result.allowed
  } catch {
    queryError.value = '安全校验服务暂不可用。'
    return false
  } finally { queryBusy.value = false }
}

async function executeSql() {
  if (queryBusy.value) return
  queryBusy.value = true
  queryError.value = ''
  queryStatus.value = ''
  sqlQueryResult.value = null
  try {
    const response = await fetch('/api/v1/query/execute', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sql: sqlText.value }) })
    const result: QueryResult = await response.json()
    sqlQueryResult.value = result
    if (!result.success) queryError.value = result.message ?? '查询执行失败。'
    else queryStatus.value = `${result.rowCount} 行 · ${result.executionTimeMs} ms${result.truncated ? ' · 已截断' : ''}`
  } catch {
    queryError.value = '查询服务暂不可用，请稍后重试。'
  } finally { queryBusy.value = false }
}

async function askAi(mode: 'generate' | 'query') {
  if (aiBusy.value || aiRetryWait.value > 0) return
  aiBusy.value = true
  aiError.value = ''
  aiStatus.value = ''
  aiResult.value = null
  try {
    const response = await fetch(`/api/v1/ai/${mode}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ question: aiQuestion.value, modelId: selectedModelId.value }) })
    const result: AiResponse = await response.json()
    aiResult.value = result
    if (result.errorCode === 'AI_RATE_LIMITED') startAiCooldown(result.providerFailure)
    if (result.sql) sqlText.value = result.sql
    if (result.clarificationRequired) aiStatus.value = '需要补充问题范围'
    else if (!result.success) aiError.value = result.message ?? 'AI 分析未完成。'
    else if (!result.sql) aiStatus.value = '该问题暂时无法映射到授权业务数据。'
    else if (mode === 'query') {
      const query = result.queryResult
      if (query?.success) aiStatus.value = `${query.rowCount} 行 · 查询 ${query.executionTimeMs} ms`
      else aiError.value = query?.message ?? 'SQL 已生成，但查询未成功。'
    } else aiStatus.value = result.validation?.allowed ? 'SQL 已生成并通过服务端安全校验，可确认执行。' : 'SQL 未通过安全校验。'
  } catch {
    aiError.value = 'AI 服务暂不可用，请检查后端配置并重试。'
  } finally { aiBusy.value = false }
}

function startAiCooldown(failure: ProviderFailure | null) {
  if (aiRetryTimer !== undefined) window.clearInterval(aiRetryTimer)
  const fallback = failure?.category === 'PROVIDER_GLOBAL_LIMIT' || failure?.category === 'QUOTA_OR_BILLING' ? 300
    : failure?.category === 'MODEL_CAPACITY' ? 30 : failure?.category === 'REQUEST_RATE' ? 5 : 60
  aiRetryWait.value = Math.max(1, failure?.retryAfterSeconds ?? fallback)
  aiRetryTimer = window.setInterval(() => {
    aiRetryWait.value = Math.max(0, aiRetryWait.value - 1)
    if (aiRetryWait.value === 0 && aiRetryTimer !== undefined) {
      window.clearInterval(aiRetryTimer)
      aiRetryTimer = undefined
    }
  }, 1000)
}

async function executeGeneratedSql() {
  if (!aiResult.value?.validation?.allowed || !aiResult.value.sql) return
  sqlText.value = aiResult.value.sql
  await executeSql()
  if (sqlQueryResult.value) aiResult.value.queryResult = sqlQueryResult.value
}

onBeforeUnmount(() => { if (aiRetryTimer !== undefined) window.clearInterval(aiRetryTimer) })

function displayCell(value: unknown) {
  if (value === null || value === undefined) return 'NULL'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

const money = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 })
const integer = new Intl.NumberFormat('zh-CN')

onMounted(async () => {
  void loadAiModels()
  try {
    const response = await fetch('/api/v1/analytics/overview', { headers: { Accept: 'application/json' } })
    if (!response.ok) throw new Error(`数据接口返回 HTTP ${response.status}`)
    const data: Overview = await response.json()
    if (!data.syntheticData || data.datasetCode !== 'SYNTHETIC_ECOMMERCE_V1') {
      throw new Error('数据集标记未通过校验，已停止展示。')
    }
    overview.value = data
    loadState.value = 'ready'
  } catch (error) {
    loadState.value = 'error'
    errorMessage.value = error instanceof Error ? error.message : '无法读取数据库分析结果。'
  }
})

async function loadAiModels() {
  try {
    const response = await fetch('/api/v1/ai/models', { headers: { Accept: 'application/json' } })
    if (!response.ok) throw new Error()
    const data: { models: AiModelOption[]; defaultModelId: string } = await response.json()
    aiModels.value = data.models
    selectedModelId.value = data.defaultModelId
  } catch {
    aiModelLoadError.value = '模型列表暂不可用，请检查后端配置。'
  }
}

</script>

<template>
  <main class="workspace">
    <header class="topbar">
      <a class="brand" href="#home" aria-label="DataPilot 首页">
        <span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span>
        <span class="brand-name">DataPilot</span>
      </a>
      <div class="stage-label"><span></span> 第五阶段 · 可靠 AI 分析</div>
    </header>

    <section id="home" class="hero">
      <p class="eyebrow"><span class="eyebrow-line"></span> 电商业务数据工作台</p>
      <h1>从业务事实，<br /><span>开始可验证的分析。</span></h1>
      <p class="hero-copy">分析结果直接来自本地 MySQL。指标口径以支付成功时间为准，退款单独核算。</p>
      <div v-if="loadState === 'ready' && overview" class="health-card health-card--connected" role="status">
        <span class="health-indicator" aria-hidden="true"></span>
        <span class="health-copy"><strong>数据库已连接</strong><span>已读取真实 MySQL 聚合查询结果</span></span>
        <span class="health-detail">只读账号 · MySQL 8</span>
      </div>
      <div v-else-if="loadState === 'loading'" class="health-card" role="status">
        <span class="health-indicator"></span><span class="health-copy"><strong>读取中</strong><span>正在查询本机 MySQL</span></span>
      </div>
      <div v-else class="health-card health-card--unavailable" role="alert">
        <span class="health-indicator"></span><span class="health-copy"><strong>数据暂不可用</strong><span>{{ errorMessage }} 请先启动数据库、生成数据并运行后端。</span></span>
      </div>
    </section>

    <section v-if="loadState === 'ready' && overview" class="dashboard" aria-label="数据库分析结果">
      <div class="synthetic-banner"><span class="notice-icon">i</span><span><strong>合成数据</strong>　以下数据均由固定随机种子生成，仅用于开发演示，不代表真实业务。</span></div>

      <div class="metric-grid">
        <article class="metric-card"><span>订单总数</span><strong>{{ integer.format(overview.orderCount) }}</strong><small>笔合成订单</small></article>
        <article class="metric-card"><span>已支付订单</span><strong>{{ integer.format(overview.paidOrderCount) }}</strong><small>支付成功订单</small></article>
        <article class="metric-card"><span>累计实付金额</span><strong>{{ money.format(overview.paidSalesAmount) }}</strong><small>按 paid_at 统计本金</small></article>
        <article class="metric-card"><span>已完成退款</span><strong>{{ money.format(overview.completedRefundAmount) }}</strong><small>单独列示，不冲减实付</small></article>
      </div>

      <DataVisualizations :overview="overview" />
    </section>

    <section class="ai-workbench" aria-label="AI 智能分析">
      <div class="sql-heading"><div><p class="section-kicker">AI TEXT2SQL</p><h2>自然语言智能分析</h2><p>模型只生成 SQL；服务端重新校验后才允许只读执行。</p></div><span>限量重试 · 查询限制沿用安全引擎</span></div>
      <label class="model-picker" for="ai-model">使用模型<select id="ai-model" v-model="selectedModelId" :disabled="aiBusy || !aiModels.some(model => model.available)"><option v-for="model in aiModels" :key="model.id" :value="model.id" :disabled="!model.available">{{ model.provider }} · {{ model.model }}{{ model.defaultModel ? '（默认）' : '' }}{{ model.available ? '' : '（不可用）' }}</option></select></label>
      <p v-if="aiModelLoadError" class="query-error" role="status">{{ aiModelLoadError }}</p>
      <div class="ai-examples"><button v-for="question in aiQuestions" :key="question" type="button" @click="aiQuestion = question">{{ question }}</button></div>
      <label class="sr-only" for="ai-question">自然语言分析问题</label>
      <textarea id="ai-question" v-model="aiQuestion" aria-label="自然语言分析问题" maxlength="500" placeholder="例如：比较 9 月和 10 月各地区实付金额"></textarea>
      <div class="sql-actions"><button type="button" class="button-secondary" :disabled="aiBusy || aiRetryWait > 0 || !aiQuestion.trim()" @click="askAi('generate')">{{ aiBusy ? '分析中…' : aiRetryWait > 0 ? `请等待 ${aiRetryWait}s` : '生成 SQL' }}</button><button type="button" class="button-primary" :disabled="aiBusy || aiRetryWait > 0 || !aiQuestion.trim()" @click="askAi('query')">{{ aiBusy ? '分析中…' : aiRetryWait > 0 ? `请等待 ${aiRetryWait}s` : '一键生成并分析' }}</button><span v-if="aiStatus" class="query-status" role="status">{{ aiStatus }}</span></div>
      <p v-if="aiError" class="query-error" role="alert">{{ aiError }}</p>
      <article v-if="aiResult" class="ai-answer" aria-live="polite">
        <div class="ai-meta"><span>{{ aiResult.provider }} / {{ aiResult.model }}</span><span>模型 {{ aiResult.modelResponseTimeMs }} ms</span><span v-if="aiResult.queryResult">SQL {{ aiResult.queryResult.executionTimeMs }} ms</span><span v-if="aiResult.tokenUsage?.totalTokens != null">{{ aiResult.tokenUsage.totalTokens }} tokens</span><span>{{ aiResult.promptVersion }}</span></div>
        <p v-if="aiResult.intent" class="ai-intent">{{ aiResult.intent }}</p>
        <p v-if="aiResult.explanation" class="ai-explanation">{{ aiResult.explanation }}</p>
        <p v-if="aiResult.clarificationRequired" class="ai-clarification">{{ aiResult.clarificationQuestion }}</p>
        <p v-if="!aiResult.sql && aiResult.message" class="ai-clarification">{{ aiResult.message }}</p>
        <p v-if="aiResult.providerFailure" class="provider-limit">限流分类：{{ aiResult.providerFailure.category }} · HTTP {{ aiResult.providerFailure.status }}<template v-if="aiResult.providerFailure.retryAfterSeconds != null"> · 建议等待 {{ aiResult.providerFailure.retryAfterSeconds }} 秒</template><template v-if="aiResult.providerFailure.remainingRequests != null"> · 剩余请求 {{ aiResult.providerFailure.remainingRequests }}</template></p>
        <details v-if="aiResult.sql" class="ai-sql"><summary>查看生成的 SQL</summary><pre>{{ aiResult.sql }}</pre><p v-if="aiResult.validation && !aiResult.validation.allowed" class="query-error">{{ aiResult.validation.message }}</p><button v-if="aiResult.validation?.allowed && !aiResult.queryResult" type="button" class="button-primary" :disabled="queryBusy" @click="executeGeneratedSql">确认执行 SQL</button></details>
        <DataVisualizations v-if="aiResult.queryResult?.success" :result="aiResult.queryResult" :question="aiQuestion" />
      </article>
    </section>

    <section class="sql-workbench" aria-label="SQL 查询工作台">
      <div class="sql-heading"><div><p class="section-kicker">READ ONLY SQL</p><h2>手动查询工作台</h2><p>仅允许访问授权业务表与字段。查询通过服务端 AST 校验，使用只读账号执行。</p></div><span>最多 200 行 · 默认 5 秒</span></div>
      <div class="sql-examples"><button v-for="example in sqlExamples" :key="example.label" type="button" @click="sqlText = example.sql">{{ example.label }}</button></div>
      <label class="sr-only" for="sql-editor">SQL 查询语句</label>
      <textarea id="sql-editor" v-model="sqlText" spellcheck="false" aria-label="SQL 查询编辑器"></textarea>
      <div class="sql-actions"><button type="button" class="button-secondary" :disabled="queryBusy" @click="validateSql">安全校验</button><button type="button" class="button-primary" :disabled="queryBusy" @click="executeSql">{{ queryBusy ? '查询中…' : '执行查询' }}</button><span v-if="queryStatus" class="query-status" role="status">{{ queryStatus }}</span></div>
      <p v-if="queryError" class="query-error" role="alert">{{ queryError }}</p>
      <div v-if="sqlQueryResult?.success" class="query-table-wrap">
        <table class="query-table"><thead><tr><th v-for="column in sqlQueryResult.columns" :key="column.name">{{ column.name }}<small>{{ column.type }}</small></th></tr></thead><tbody><tr v-for="(row, rowIndex) in sqlQueryResult.rows" :key="rowIndex"><td v-for="column in sqlQueryResult.columns" :key="column.name">{{ displayCell(row[column.name]) }}</td></tr></tbody></table>
        <p v-if="sqlQueryResult.truncated" class="query-truncated">结果超过 200 行，已限制返回结果。</p>
      </div>
    </section>

    <footer class="footer">
      <span>DataPilot <span class="footer-divider">/</span> 为可靠分析而构建</span>
      <span>JAVA 21 <i></i> VUE 3 <i></i> MYSQL 8 <i></i> ECHARTS</span>
    </footer>
  </main>
</template>
