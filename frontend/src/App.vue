<script setup lang="ts">
import { onMounted, ref } from 'vue'

type HealthState = 'checking' | 'connected' | 'unavailable'

const backendState = ref<HealthState>('checking')
const backendMessage = ref('正在连接本机服务…')

const stateLabel: Record<HealthState, string> = {
  checking: '检查中',
  connected: '服务已就绪',
  unavailable: '未连接',
}

onMounted(async () => {
  try {
    const response = await fetch('/actuator/health/liveness', {
      headers: { Accept: 'application/json' },
    })
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`)
    }

    const health: unknown = await response.json()
    if (
      typeof health !== 'object' ||
      health === null ||
      !('status' in health) ||
      health.status !== 'UP'
    ) {
      throw new Error('后端健康检查未返回 UP')
    }

    backendState.value = 'connected'
    backendMessage.value = 'Spring Boot 后端已响应健康检查'
  } catch {
    backendState.value = 'unavailable'
    backendMessage.value = '请在 PowerShell 中运行 .\scripts\start-backend.ps1'
  }
})
</script>

<template>
  <main class="workspace">
    <header class="topbar">
      <a class="brand" href="#home" aria-label="DataPilot 首页">
        <span class="brand-mark" aria-hidden="true">
          <i></i><i></i><i></i>
        </span>
        <span class="brand-name">DataPilot</span>
      </a>

      <div class="stage-label"><span></span> 第一阶段 · 工程基础</div>
    </header>

    <section id="home" class="hero" aria-labelledby="page-title">
      <p class="eyebrow"><span class="eyebrow-line"></span> 面向可信业务数据的 AI 分析平台</p>
      <h1 id="page-title">让每一个结论，<br /><span>都能回到数据本身。</span></h1>
      <p class="hero-copy">
        从业务问题到可验证的 SQL，再到清晰的分析图表。DataPilot 正在搭建每一步都可检查的数据分析链路。
      </p>

      <div class="health-card" :class="`health-card--${backendState}`" role="status" aria-live="polite">
        <span class="health-indicator" aria-hidden="true"></span>
        <span class="health-copy">
          <strong>{{ stateLabel[backendState] }}</strong>
          <span>{{ backendMessage }}</span>
        </span>
        <span class="health-detail">本机运行 · 127.0.0.1:8080</span>
      </div>
    </section>

    <section class="build-panel" aria-labelledby="build-title">
      <div class="panel-heading">
        <div>
          <p class="section-kicker">从可运行的基础开始</p>
          <h2 id="build-title">分析工作台，正在准备就绪</h2>
        </div>
        <span class="progress-label"><span></span> 阶段 1 / 5</span>
      </div>

      <div class="milestones" aria-label="项目开发阶段">
        <article class="milestone milestone--active">
          <span class="milestone-number">01</span>
          <h3>工程基础</h3>
          <p>服务、测试与本地开发环境</p>
          <span class="milestone-state">当前阶段</span>
        </article>
        <span class="milestone-connector" aria-hidden="true"></span>
        <article class="milestone">
          <span class="milestone-number">02</span>
          <h3>业务数据</h3>
          <p>可重复生成的电商合成数据</p>
          <span class="milestone-state">待开发</span>
        </article>
        <span class="milestone-connector" aria-hidden="true"></span>
        <article class="milestone">
          <span class="milestone-number">03</span>
          <h3>查询安全</h3>
          <p>SQL AST 校验与只读执行</p>
          <span class="milestone-state">待开发</span>
        </article>
        <span class="milestone-connector" aria-hidden="true"></span>
        <article class="milestone">
          <span class="milestone-number">04</span>
          <h3>分析呈现</h3>
          <p>真实结果、图表与来源追溯</p>
          <span class="milestone-state">待开发</span>
        </article>
        <span class="milestone-connector" aria-hidden="true"></span>
        <article class="milestone">
          <span class="milestone-number">05</span>
          <h3>AI 闭环</h3>
          <p>Text2SQL 执行反馈与评测</p>
          <span class="milestone-state">待开发</span>
        </article>
      </div>

      <p class="data-notice">
        <span class="notice-icon" aria-hidden="true">i</span>
        <span><strong>数据来源说明</strong>　尚未连接业务数据库或大模型。本页面暂不展示业务指标、查询结果或模拟数据。</span>
      </p>
    </section>

    <footer class="footer">
      <span>DataPilot <span class="footer-divider">/</span> 为可靠分析而构建</span>
      <span>JAVA 21 <span class="footer-dot"></span> VUE 3 <span class="footer-dot"></span> MYSQL 8</span>
    </footer>
  </main>
</template>
