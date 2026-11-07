const DATE_VALUE = /^\d{4}[-/]\d{1,2}([-/]\d{1,2})?$/
const NUMERIC_TYPE = /(tinyint|smallint|mediumint|bigint|int|decimal|numeric|float|double|real|number)/i

export function recommendChart(columns, rows, question = '') {
  if (!rows?.length) return { type: 'table', reason: '查询没有返回数据。' }
  const numeric = columns.filter((column) => isNumeric(column, rows))
  const dimensions = columns.filter((column) => !numeric.includes(column))
  if (!numeric.length) return { type: 'table', reason: '结果没有可绘制的数值指标。' }
  if (numeric.every((column) => rows.every((row) => row[column.name] == null))) return { type: 'table', reason: '数值指标为空，保留原始表格。' }
  if (numeric.some((column) => rows.some((row) => row[column.name] != null && safeNumber(row[column.name]) === null))) {
    return { type: 'table', reason: '数值超出浏览器安全图表精度范围，保留原始表格避免误导。' }
  }
  if (rows.length === 1 && dimensions.length === 0 && numeric.length <= 4) {
    return { type: 'kpi', metrics: numeric.map((metric) => ({ name: metric.name, format: metricFormat(metric) })) }
  }
  if (dimensions.length === 2 && numeric.length === 1 && rows.length <= 30) {
    const time = dimensions.find((column) => isDateDimension(column, rows))
    const category = dimensions.find((column) => column !== time)
    if (time && category) {
      const periods = [...new Set(rows.map((row) => String(row[time.name])))].sort()
      const categories = [...new Set(rows.map((row) => String(row[category.name])))].sort()
      if (periods.length >= 2 && categories.length <= 12) {
        const metric = numeric[0]
        const useTrend = periods.length >= 4
        return {
          type: useTrend ? 'line' : 'bar',
          dimension: useTrend ? time.name : category.name,
          seriesDimension: useTrend ? category.name : time.name,
          seriesValues: useTrend ? categories : periods,
          categories: useTrend ? periods : categories,
          metrics: [{ name: metric.name, format: metricFormat(metric) }],
        }
      }
    }
  }
  if (dimensions.length !== 1 || numeric.length > 3 || rows.length > 30) {
    return { type: 'table', reason: '结果维度较多或规模较大，使用表格更清晰。' }
  }
  const dimension = dimensions[0]
  if (isDateDimension(dimension, rows)) return { type: 'line', dimension: dimension.name, metrics: numeric.map((metric) => ({ name: metric.name, format: metricFormat(metric) })) }
  if (numeric.length === 1 && rows.length <= 8 && /(占比|比例|构成|组成)/.test(question)) {
    const values = rows.map((row) => Number(row[numeric[0].name]))
    if (values.every((value) => Number.isFinite(value) && value >= 0)) {
      return { type: 'pie', dimension: dimension.name, metric: numeric[0].name, format: metricFormat(numeric[0]) }
    }
  }
  return { type: 'bar', dimension: dimension.name, metrics: numeric.map((metric) => ({ name: metric.name, format: metricFormat(metric) })) }
}

export function buildChartOption(recommendation, rows) {
  if (!['bar', 'line', 'pie'].includes(recommendation.type) || !rows?.length) return null
  if (recommendation.type === 'pie') {
    return {
      tooltip: { trigger: 'item', valueFormatter: (value) => formatValue(value, recommendation.format) },
      legend: { bottom: 0, type: 'scroll' },
      series: [{ type: 'pie', radius: ['38%', '68%'], data: rows.map((row) => ({ name: String(row[recommendation.dimension]), value: safeNumber(row[recommendation.metric]) })) }],
    }
  }
  if (recommendation.seriesDimension) {
    const { dimension, seriesDimension, seriesValues, categories, metrics } = recommendation
    const metric = metrics[0]
    return {
      tooltip: { trigger: 'axis', valueFormatter: (value) => formatValue(value, metric.format) },
      legend: { top: 0 },
      grid: { left: 58, right: 18, top: 35, bottom: 42, containLabel: true },
      xAxis: { type: 'category', data: categories, axisTick: { show: false }, axisLabel: { color: '#77847d', rotate: categories.length > 8 ? 30 : 0 } },
      yAxis: { type: 'value', axisLabel: { color: '#77847d', formatter: (value) => formatValue(value, metric.format) }, splitLine: { lineStyle: { color: '#edf1ee' } } },
      series: seriesValues.map((seriesValue) => ({
        name: seriesValue,
        type: recommendation.type,
        smooth: recommendation.type === 'line',
        barMaxWidth: 36,
        data: categories.map((categoryValue) => {
          const row = rows.find((item) => String(item[dimension]) === categoryValue && String(item[seriesDimension]) === seriesValue)
          return row ? safeNumber(row[metric.name]) : null
        }),
      })),
    }
  }
  const axis = { type: 'category', data: rows.map((row) => String(row[recommendation.dimension])), axisTick: { show: false }, axisLabel: { color: '#77847d' } }
  return {
    tooltip: { trigger: 'axis', valueFormatter: (value) => formatValue(value, recommendation.metrics[0]?.format) },
    legend: recommendation.metrics.length > 1 ? { top: 0 } : undefined,
    grid: { left: 58, right: 18, top: recommendation.metrics.length > 1 ? 35 : 18, bottom: 42, containLabel: true },
    xAxis: { ...axis, axisLabel: { ...axis.axisLabel, rotate: rows.length > 8 ? 30 : 0 } },
    yAxis: { type: 'value', axisLabel: { color: '#77847d', formatter: (value) => formatValue(value, recommendation.metrics[0]?.format) }, splitLine: { lineStyle: { color: '#edf1ee' } } },
    series: recommendation.metrics.map((metric) => ({
      name: metric.name,
      type: recommendation.type,
      smooth: recommendation.type === 'line',
      barMaxWidth: 36,
      data: rows.map((row) => safeNumber(row[metric.name])),
    })),
  }
}

export function buildInsights(columns, rows, question = '') {
  if (!rows?.length) return [{ text: '查询结果为空，暂时无法形成数据结论。', basis: '当前 SQL 返回 0 行。' }]
  const numeric = columns.filter((column) => isNumeric(column, rows))
  if (!numeric.length) return [{ text: '结果中没有可比较的数值指标。', basis: '结论仅从本次 SQL 返回字段计算。' }]
  if (numeric.every((column) => rows.every((row) => row[column.name] == null))) return [{ text: '数值指标为空，暂时无法形成排序或趋势结论。', basis: '本次 SQL 返回的数值字段均为 NULL。' }]
  if (numeric.some((column) => rows.some((row) => row[column.name] != null && safeNumber(row[column.name]) === null))) {
    return [{ text: '数值超出浏览器安全比较精度，未生成排序或趋势结论。', basis: '请使用原始查询结果核对高精度数值。' }]
  }
  if (rows.length === 1) {
    return numeric.slice(0, 3).map((metric) => ({
      text: `${localizedName(metric.name)}：${formatValue(rows[0][metric.name], metricFormat(metric))}`,
      basis: `取自查询结果字段「${metric.name}」。`,
    }))
  }
  const change = numeric.find((column) => /(change|delta|difference|变化|差额)/i.test(column.name))
  if (change) {
    const positive = /(增长|上升|增加)/.test(question)
    const sorted = [...rows].sort((a, b) => toDecimal(a[change.name]) - toDecimal(b[change.name]))
    const selected = positive ? sorted.at(-1) : sorted[0]
    const dimension = columns.find((column) => column !== change && !isNumeric(column, rows))
    if (selected && dimension) {
      if (!positive && rows.every((row) => toDecimal(row[change.name]) >= 0)) {
        return [{ text: `本次比较中没有下降项；${String(selected[dimension.name])}的增幅最小，为 ${formatValue(selected[change.name], metricFormat(change))}。`, basis: `按 SQL 返回字段「${change.name}」比较，共比较 ${rows.length} 行；不表示因果关系。` }]
      }
      const direction = toDecimal(selected[change.name]) < 0 ? '下降' : '增长'
      return [{ text: `${String(selected[dimension.name])}的${localizedName(change.name)}${direction}最多：${formatValue(selected[change.name], metricFormat(change))}。`, basis: `按 SQL 返回字段「${change.name}」比较，共比较 ${rows.length} 行；此为数值对比，不表示因果关系。` }]
    }
  }
  const dateColumn = columns.find((column) => isDateDimension(column, rows))
  const metric = numeric.find((column) => /(amount|sales|paid|refund|金额|销售额|实付)/i.test(column.name)) ?? numeric[0]
  if (dateColumn && rows.length >= 2) {
    const groupDimension = columns.find((column) => column !== dateColumn && !isNumeric(column, rows))
    if (groupDimension && /(地区|region)/i.test(`${groupDimension.name} ${question}`)) {
      const groups = new Map()
      for (const row of rows) {
        const key = String(row[groupDimension.name])
        groups.set(key, [...(groups.get(key) ?? []), row])
      }
      const comparisons = [...groups.entries()].flatMap(([name, values]) => {
        const periods = values.sort((a, b) => String(a[dateColumn.name]).localeCompare(String(b[dateColumn.name])))
        if (periods.length < 2) return []
        const delta = toDecimal(periods.at(-1)[metric.name]) - toDecimal(periods[0][metric.name])
        return [{ name, first: periods[0][dateColumn.name], last: periods.at(-1)[dateColumn.name], delta }]
      })
      if (comparisons.length) {
        const selected = /(增长|上升|增加)/.test(question)
          ? comparisons.reduce((best, current) => current.delta > best.delta ? current : best)
          : comparisons.reduce((best, current) => current.delta < best.delta ? current : best)
        if (!/(增长|上升|增加)/.test(question) && comparisons.every((item) => item.delta >= 0)) {
          return [{ text: `本次比较中没有地区销售额下降；${selected.name}的增幅最小，为 ${formatValue(selected.delta, metricFormat(metric))}。`, basis: `按各地区首末期字段「${metric.name}」计算，共比较 ${comparisons.length} 个地区；不表示因果关系。` }]
        }
        const direction = selected.delta < 0 ? '下降' : selected.delta > 0 ? '增长' : '持平'
        return [{ text: `${selected.name}在 ${String(selected.first)} 至 ${String(selected.last)} 的${localizedName(metric.name)}${direction}最多：${formatValue(Math.abs(selected.delta), metricFormat(metric))}。`, basis: `按各地区首末期字段「${metric.name}」计算，共比较 ${comparisons.length} 个地区；不表示因果关系。` }]
      }
    }
    const sorted = [...rows].sort((a, b) => String(a[dateColumn.name]).localeCompare(String(b[dateColumn.name])))
    const first = toDecimal(sorted[0][metric.name])
    const last = toDecimal(sorted.at(-1)[metric.name])
    const delta = last - first
    const direction = delta > 0 ? '增长' : delta < 0 ? '下降' : '持平'
    return [{ text: `${localizedName(metric.name)}从 ${String(sorted[0][dateColumn.name])} 到 ${String(sorted.at(-1)[dateColumn.name])}${direction} ${formatValue(Math.abs(delta), metricFormat(metric))}。`, basis: `按时间字段「${dateColumn.name}」的首末两期 SQL 结果计算，统计范围为 ${String(sorted[0][dateColumn.name])} 至 ${String(sorted.at(-1)[dateColumn.name])}；不推断原因。` }]
  }
  const dimension = columns.find((column) => !isNumeric(column, rows))
  if (!dimension) return [{ text: '数据不足以比较多个指标。', basis: '当前结果缺少可识别的维度字段。' }]
  const top = [...rows].sort((a, b) => toDecimal(b[metric.name]) - toDecimal(a[metric.name])).slice(0, 3)
  return [{ text: `按${localizedName(metric.name)}排序，排名靠前的是：${top.map((row) => `${String(row[dimension.name])}（${formatValue(row[metric.name], metricFormat(metric))}）`).join('、')}。`, basis: `基于本次 SQL 返回的 ${rows.length} 行，按字段「${metric.name}」降序计算；不表示因果关系。` }]
}

export function formatValue(value, format = 'number') {
  const number = safeNumber(value)
  if (number === null) return value == null ? '—' : String(value)
  if (format === 'percent') return new Intl.NumberFormat('zh-CN', { style: 'percent', maximumFractionDigits: 2 }).format(number)
  if (format === 'currency') return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(number)
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(number)
}

export function metricFormat(column) {
  const name = `${column.name} ${column.type ?? ''}`.toLowerCase()
  if (/(rate|ratio|percent|share|portion|退款率|增长率|百分比|占比)/i.test(name)) return 'percent'
  if (/(amount|sales|paid|refund|revenue|金额|销售额|实付|退款额)/i.test(name)) return 'currency'
  return 'number'
}

function isNumeric(column, rows) {
  if (NUMERIC_TYPE.test(column.type ?? '')) return true
  const values = rows.map((row) => row[column.name]).filter((value) => value != null && value !== '')
  return values.length > 0 && values.every((value) => typeof value === 'number' || (typeof value === 'string' && /^-?\d+(\.\d+)?$/.test(value)))
}

function isDateDimension(column, rows) {
  if (/(date|time|month|day)/i.test(`${column.name} ${column.type ?? ''}`)) return true
  return rows.every((row) => typeof row[column.name] === 'string' && DATE_VALUE.test(row[column.name]))
}

function safeNumber(value) {
  if (value == null || value === '') return null
  const number = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(number)) return null
  if (Math.abs(number) > Number.MAX_SAFE_INTEGER) return null
  if (typeof value === 'string' && value.includes('.')) {
    const integerDigits = value.replace(/^[+-]/, '').split('.')[0].replace(/^0+/, '').length
    if (integerDigits > 13) return null
  }
  return number
}

function toDecimal(value) {
  const number = safeNumber(value)
  return number === null ? 0 : number
}

function localizedName(name) {
  const value = name.toLowerCase()
  if (/sales_amount|salesamount/.test(value)) return '销售额'
  if (/paid_amount|paidamount/.test(value)) return '实付金额'
  if (/refund_amount|refundamount/.test(value)) return '退款金额'
  if (/sales_change|saleschange|change/.test(value)) return '销售额变化'
  if (/order_count|ordercount/.test(value)) return '订单数'
  return name
}
