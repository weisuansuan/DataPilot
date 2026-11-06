<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { init, use, type ECharts } from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([BarChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

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

const loadState = ref<LoadState>('loading')
const errorMessage = ref('')
const overview = ref<Overview | null>(null)
const regionChartElement = ref<HTMLDivElement | null>(null)
const refundChartElement = ref<HTMLDivElement | null>(null)
let regionChart: ECharts | undefined
let refundChart: ECharts | undefined

const money = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 })
const integer = new Intl.NumberFormat('zh-CN')
const percent = new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 1 })

function renderCharts(data: Overview) {
  if (regionChartElement.value) {
    regionChart = init(regionChartElement.value)
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

  if (refundChartElement.value) {
    refundChart = init(refundChartElement.value)
    refundChart.setOption({
      color: ['#c77d5f'],
      tooltip: { trigger: 'axis', valueFormatter: (value: string | number) => percent.format(Number(value)) },
      grid: { left: 48, right: 16, top: 16, bottom: 25 },
      xAxis: { type: 'category', data: data.categoryRefundRates.map((item) => item.categoryName), axisTick: { show: false }, axisLine: { lineStyle: { color: '#e8eeea' } }, axisLabel: { color: '#77847d', interval: 0 } },
      yAxis: { type: 'value', axisLabel: { color: '#98a29c', formatter: (value: number) => `${Math.round(value * 100)}%` }, splitLine: { lineStyle: { color: '#edf1ee' } } },
      series: [{ name: '退款件数 / 已售件数', type: 'bar', data: data.categoryRefundRates.map((item) => item.refundRate), barMaxWidth: 42, itemStyle: { borderRadius: [5, 5, 0, 0] } }],
    })
  }
}

function resizeCharts() {
  regionChart?.resize()
  refundChart?.resize()
}

onMounted(async () => {
  try {
    const response = await fetch('/api/v1/analytics/overview', { headers: { Accept: 'application/json' } })
    if (!response.ok) throw new Error(`数据接口返回 HTTP ${response.status}`)
    const data: Overview = await response.json()
    if (!data.syntheticData || data.datasetCode !== 'SYNTHETIC_ECOMMERCE_V1') {
      throw new Error('数据集标记未通过校验，已停止展示。')
    }
    overview.value = data
    loadState.value = 'ready'
    requestAnimationFrame(() => renderCharts(data))
    window.addEventListener('resize', resizeCharts)
  } catch (error) {
    loadState.value = 'error'
    errorMessage.value = error instanceof Error ? error.message : '无法读取数据库分析结果。'
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  regionChart?.dispose()
  refundChart?.dispose()
})
</script>

<template>
  <main class="workspace">
    <header class="topbar">
      <a class="brand" href="#home" aria-label="DataPilot 首页">
        <span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span>
        <span class="brand-name">DataPilot</span>
      </a>
      <div class="stage-label"><span></span> 第二阶段 · 合成业务数据</div>
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

      <div class="chart-grid">
        <article class="chart-card">
          <div class="chart-heading"><div><p class="section-kicker">REGIONAL SALES</p><h2>地区实付金额变化</h2></div><span class="chart-caption">2026 年 9 月 / 10 月</span></div>
          <div ref="regionChartElement" class="chart" aria-label="各地区九月和十月实付金额柱状图"></div>
          <div class="insight-list">
            <p v-for="region in [...overview.regionComparisons].sort((a, b) => a.salesChange - b.salesChange).slice(0, 3)" :key="region.regionCode">
              <span>{{ region.regionName }}</span><strong :class="region.salesChange < 0 ? 'negative' : ''">{{ region.salesChange < 0 ? '−' : '+' }}{{ money.format(Math.abs(region.salesChange)) }} <small>({{ percent.format(region.salesChangePercent) }})</small></strong>
            </p>
          </div>
          <p class="metric-definition">实付金额按 payment.paid_at 归月；统计支付成功本金，退款另列。</p>
        </article>

        <article class="chart-card">
          <div class="chart-heading"><div><p class="section-kicker">REFUND RATE</p><h2>各品类商品退款率</h2></div><span class="chart-caption">退款件数 / 已售件数</span></div>
          <div ref="refundChartElement" class="chart" aria-label="各商品品类退款率柱状图"></div>
          <div class="insight-list category-rates">
            <p v-for="category in [...overview.categoryRefundRates].sort((a, b) => b.refundRate - a.refundRate)" :key="category.categoryCode">
              <span>{{ category.categoryName }} <small>{{ integer.format(category.refundedUnits) }} / {{ integer.format(category.soldUnits) }} 件</small></span><strong :class="category.categoryCode === 'electronics' ? 'negative' : ''">{{ percent.format(category.refundRate) }}</strong>
            </p>
          </div>
          <p class="metric-definition">仅统计已支付订单；退款按退款明细关联的商品行归类，观察截止 {{ overview.refundObservationCutoff }}。</p>
        </article>
      </div>

      <div class="source-note"><strong>数据来源</strong><span>sales_order · payment · order_item · product · product_category · refund</span><small>数据集 {{ overview.datasetCode }} · 生成时间 {{ new Date(overview.generatedAt).toLocaleString('zh-CN') }}</small></div>
    </section>

    <footer class="footer">
      <span>DataPilot <span class="footer-divider">/</span> 为可靠分析而构建</span>
      <span>JAVA 21 <i></i> VUE 3 <i></i> MYSQL 8 <i></i> ECHARTS</span>
    </footer>
  </main>
</template>
