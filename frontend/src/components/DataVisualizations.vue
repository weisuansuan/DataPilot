<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use, type ECharts } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { buildChartOption, buildInsights, formatValue, recommendChart } from '../analysis/result-analysis.js'
import type { ChartRecommendation } from '../analysis/result-analysis.js'

use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

interface QueryColumn { name: string; type: string }
interface QueryResult { columns: QueryColumn[]; rows: Record<string, unknown>[]; truncated: boolean; executionTimeMs: number }
interface RegionComparison { regionCode: string; regionName: string; septemberSales: number; octoberSales: number; salesChange: number; salesChangePercent: number }
interface CategoryRefundRate { categoryCode: string; categoryName: string; soldUnits: number; refundedUnits: number; refundRate: number }
interface Overview { datasetCode: string; generatedAt: string; refundObservationCutoff: string; regionComparisons: RegionComparison[]; categoryRefundRates: CategoryRefundRate[] }

const props = defineProps<{ overview?: Overview | null; result?: QueryResult | null; question?: string }>()
const money = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 })
const integer = new Intl.NumberFormat('zh-CN')
const percent = new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 1 })
const regionElement = ref<HTMLDivElement | null>(null)
const refundElement = ref<HTMLDivElement | null>(null)
const resultElement = ref<HTMLDivElement | null>(null)
const view = ref<'chart' | 'table'>('chart')
let regionChart: ECharts | undefined
let refundChart: ECharts | undefined
let resultChart: ECharts | undefined

const recommendation = (): ChartRecommendation => props.result ? recommendChart(props.result.columns, props.result.rows, props.question) : { type: 'table', reason: '暂无可视化结果。' }
const insights = () => props.result ? buildInsights(props.result.columns, props.result.rows, props.question) : []
const chartOption = () => props.result ? buildChartOption(recommendation(), props.result.rows) : null
const isChartAvailable = () => ['bar', 'line', 'pie'].includes(recommendation().type)
const hasChartView = () => isChartAvailable() || recommendation().type === 'kpi'

function renderDashboard() {
  if (props.overview && regionElement.value) {
    regionChart = regionChart ?? init(regionElement.value)
    const data = props.overview
    regionChart.setOption({
      color: ['#a9c7b8', '#267c66'],
      tooltip: { trigger: 'axis', valueFormatter: (value: string | number) => money.format(Number(value)) },
      legend: { top: 0, right: 0, itemWidth: 9, itemHeight: 9, textStyle: { color: '#78857f' } },
      grid: { left: 48, right: 15, top: 38, bottom: 22 },
      xAxis: { type: 'category', data: data.regionComparisons.map((item) => item.regionName), axisTick: { show: false }, axisLine: { lineStyle: { color: '#e8eeea' } }, axisLabel: { color: '#77847d' } },
      yAxis: { type: 'value', axisLabel: { color: '#98a29c', formatter: (value: number) => `${Math.round(value / 10000)}万` }, splitLine: { lineStyle: { color: '#edf1ee' } } },
      series: [
        { name: '9 月实付', type: 'bar', data: data.regionComparisons.map((item) => item.septemberSales), barMaxWidth: 25, itemStyle: { borderRadius: [4, 4, 0, 0] } },
        { name: '10 月实付', type: 'bar', data: data.regionComparisons.map((item) => item.octoberSales), barMaxWidth: 25, itemStyle: { borderRadius: [4, 4, 0, 0] } },
      ],
    })
  }
  if (props.overview && refundElement.value) {
    refundChart = refundChart ?? init(refundElement.value)
    const data = props.overview
    refundChart.setOption({
      color: ['#c77d5f'], tooltip: { trigger: 'axis', valueFormatter: (value: string | number) => percent.format(Number(value)) },
      grid: { left: 48, right: 16, top: 16, bottom: 25 },
      xAxis: { type: 'category', data: data.categoryRefundRates.map((item) => item.categoryName), axisTick: { show: false }, axisLine: { lineStyle: { color: '#e8eeea' } }, axisLabel: { color: '#77847d', interval: 0 } },
      yAxis: { type: 'value', axisLabel: { color: '#98a29c', formatter: (value: number) => `${Math.round(value * 100)}%` }, splitLine: { lineStyle: { color: '#edf1ee' } } },
      series: [{ name: '退款件数 / 已售件数', type: 'bar', data: data.categoryRefundRates.map((item) => item.refundRate), barMaxWidth: 42, itemStyle: { borderRadius: [5, 5, 0, 0] } }],
    })
  }
}

async function renderResult() {
  if (recommendation().type === 'kpi') {
    resultChart?.dispose()
    resultChart = undefined
    view.value = 'chart'
    return
  }
  if (!isChartAvailable()) {
    resultChart?.dispose()
    resultChart = undefined
    view.value = 'table'
    return
  }
  view.value = 'chart'
  await nextTick()
  if (!resultElement.value) return
  resultChart = resultChart ?? init(resultElement.value)
  resultChart.setOption({ color: ['#267c66', '#86b7a0', '#c77d5f'], ...chartOption() }, true)
}

function resize() { regionChart?.resize(); refundChart?.resize(); resultChart?.resize() }
function showTable() { view.value = 'table'; resultChart?.dispose(); resultChart = undefined }
function display(value: unknown) { return value === null || value === undefined ? 'NULL' : typeof value === 'object' ? JSON.stringify(value) : String(value) }

watch(() => [props.result, props.question], () => { void renderResult() }, { deep: true })
onMounted(() => { renderDashboard(); void renderResult(); window.addEventListener('resize', resize) })
onBeforeUnmount(() => { window.removeEventListener('resize', resize); regionChart?.dispose(); refundChart?.dispose(); resultChart?.dispose() })
</script>

<template>
  <template v-if="overview">
    <div class="chart-grid">
      <article class="chart-card">
        <div class="chart-heading"><div><p class="section-kicker">REGIONAL SALES</p><h2>地区实付金额变化</h2></div><span class="chart-caption">2026 年 9 月 / 10 月</span></div>
        <div ref="regionElement" class="chart" aria-label="各地区九月和十月实付金额柱状图"></div>
        <div class="insight-list">
          <p v-for="region in [...overview.regionComparisons].sort((a, b) => a.salesChange - b.salesChange).slice(0, 3)" :key="region.regionCode">
            <span>{{ region.regionName }}</span><strong :class="region.salesChange < 0 ? 'negative' : ''">{{ region.salesChange < 0 ? '−' : '+' }}{{ money.format(Math.abs(region.salesChange)) }} <small>({{ percent.format(region.salesChangePercent) }})</small></strong>
          </p>
        </div>
        <p class="metric-definition">实付金额按 payment.paid_at 归月；统计支付成功本金，退款另列。</p>
      </article>
      <article class="chart-card">
        <div class="chart-heading"><div><p class="section-kicker">REFUND RATE</p><h2>各品类商品退款率</h2></div><span class="chart-caption">退款件数 / 已售件数</span></div>
        <div ref="refundElement" class="chart" aria-label="各商品品类退款率柱状图"></div>
        <div class="insight-list category-rates">
          <p v-for="category in [...overview.categoryRefundRates].sort((a, b) => b.refundRate - a.refundRate)" :key="category.categoryCode">
            <span>{{ category.categoryName }} <small>{{ integer.format(category.refundedUnits) }} / {{ integer.format(category.soldUnits) }} 件</small></span><strong :class="category.categoryCode === 'electronics' ? 'negative' : ''">{{ percent.format(category.refundRate) }}</strong>
          </p>
        </div>
        <p class="metric-definition">仅统计已支付订单；退款按退款明细关联的商品行归类，观察截止 {{ overview.refundObservationCutoff }}。</p>
      </article>
    </div>
    <div class="source-note"><strong>数据来源</strong><span>sales_order · payment · order_item · product · product_category · refund</span><small>数据集 {{ overview.datasetCode }} · 生成时间 {{ new Date(overview.generatedAt).toLocaleString('zh-CN') }}</small></div>
  </template>

  <section v-if="result" class="result-visualization" aria-label="AI 查询数据可视化">
    <div class="result-toolbar">
      <div><p class="section-kicker">RESULT ANALYSIS</p><h3>查询结果分析</h3></div>
      <div class="view-switch" role="group" aria-label="结果显示方式">
        <button type="button" :disabled="!hasChartView()" :aria-pressed="view === 'chart'" @click="view = 'chart'; void renderResult()">图表</button>
        <button type="button" :aria-pressed="view === 'table'" @click="showTable">原始表格</button>
      </div>
    </div>
    <p v-if="recommendation().reason" class="chart-fallback">{{ recommendation().reason }}</p>
    <div v-if="view === 'chart' && recommendation().type === 'kpi'" class="metric-grid result-kpis">
      <article v-for="metric in recommendation().metrics ?? []" :key="metric.name" class="metric-card"><span>{{ metric.name }}</span><strong>{{ formatValue(result.rows[0]?.[metric.name], metric.format) }}</strong><small>{{ result.columns.find((column) => column.name === metric.name)?.type }}</small></article>
    </div>
    <div v-else-if="view === 'chart' && isChartAvailable()" ref="resultElement" class="chart result-chart" :aria-label="`${recommendation().type} chart of query results`"></div>
    <div v-else class="query-table-wrap">
      <table class="query-table"><thead><tr><th v-for="column in result.columns" :key="column.name">{{ column.name }}<small>{{ column.type }}</small></th></tr></thead><tbody><tr v-for="(row, rowIndex) in result.rows" :key="rowIndex"><td v-for="column in result.columns" :key="column.name">{{ display(row[column.name]) }}</td></tr><tr v-if="!result.rows.length"><td :colspan="Math.max(1, result.columns.length)" class="empty-result">无结果</td></tr></tbody></table>
      <p v-if="result.truncated" class="query-truncated">结果超过 200 行，已限制返回结果。</p>
    </div>
    <div v-if="insights().length" class="result-insights">
      <p class="section-kicker">数据结论 · 仅依据本次 SQL 结果</p>
      <article v-for="(insight, index) in insights()" :key="index"><strong>{{ insight.text }}</strong><small>{{ insight.basis }}</small></article>
    </div>
    <p v-if="!result.rows.length" class="chart-fallback">空结果不生成图表或排序结论。</p>
  </section>
</template>
