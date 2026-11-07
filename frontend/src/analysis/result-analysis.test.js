import test from 'node:test'
import assert from 'node:assert/strict'
import { buildChartOption, buildInsights, formatValue, recommendChart } from './result-analysis.js'

test('recommends a bar chart for category comparisons and builds real series', () => {
  const columns = [{ name: 'category_name', type: 'VARCHAR' }, { name: 'sales_amount', type: 'DECIMAL' }]
  const rows = [{ category_name: '家居', sales_amount: 12.5 }, { category_name: '数码', sales_amount: 18 }]
  const recommendation = recommendChart(columns, rows)
  assert.equal(recommendation.type, 'bar')
  assert.deepEqual(buildChartOption(recommendation, rows).series[0].data, [12.5, 18])
})

test('recommends line and pie charts from date and small composition dimensions', () => {
  const dateColumns = [{ name: 'paid_month', type: 'VARCHAR' }, { name: 'paid_amount', type: 'DECIMAL' }]
  assert.equal(recommendChart(dateColumns, [{ paid_month: '2026-09', paid_amount: 10 }, { paid_month: '2026-10', paid_amount: 8 }]).type, 'line')
  const pieColumns = [{ name: 'category', type: 'VARCHAR' }, { name: 'share', type: 'DECIMAL' }]
  assert.equal(recommendChart(pieColumns, [{ category: 'A', share: 0.6 }, { category: 'B', share: 0.4 }], '品类占比').type, 'pie')
})

test('recommends grouped regional bars for two periods and multi-region lines for longer trends', () => {
  const columns = [{ name: 'region_name', type: 'VARCHAR' }, { name: 'paid_month', type: 'VARCHAR' }, { name: 'paid_amount', type: 'DECIMAL' }]
  const rows = [
    { region_name: '西部', paid_month: '2026-09', paid_amount: '100.00' },
    { region_name: '西部', paid_month: '2026-10', paid_amount: '30.00' },
    { region_name: '华东', paid_month: '2026-09', paid_amount: '80.00' },
    { region_name: '华东', paid_month: '2026-10', paid_amount: '90.00' },
  ]
  const recommendation = recommendChart(columns, rows, '比较各地区月度销售')
  assert.equal(recommendation.type, 'bar')
  const option = buildChartOption(recommendation, rows)
  assert.deepEqual(option.xAxis.data, ['华东', '西部'])
  assert.deepEqual(option.series.map((series) => series.name), ['2026-09', '2026-10'])
  assert.deepEqual(option.series[1].data, [90, 30])
  const months = ['2026-07', '2026-08', '2026-09', '2026-10']
  const trendRows = months.flatMap((month, index) => [{ region_name: '西部', paid_month: month, paid_amount: 100 - index * 10 }, { region_name: '华东', paid_month: month, paid_amount: 80 + index * 10 }])
  assert.equal(recommendChart(columns, trendRows).type, 'line')
})

test('uses KPI cards for a single row and table for empty or complex results', () => {
  assert.equal(recommendChart([{ name: 'order_count', type: 'BIGINT' }], [{ order_count: 100 }]).type, 'kpi')
  assert.equal(recommendChart([{ name: 'order_count', type: 'BIGINT' }], []).type, 'table')
  assert.equal(recommendChart([{ name: 'region', type: 'VARCHAR' }, { name: 'month', type: 'VARCHAR' }, { name: 'amount', type: 'DECIMAL' }], [{ region: '北', month: '2026-01', amount: 1 }]).type, 'table')
  assert.equal(recommendChart([{ name: 'name', type: 'VARCHAR' }, { name: 'amount', type: 'DECIMAL' }], [{ name: 'x', amount: null }]).type, 'table')
  assert.match(buildInsights([{ name: 'amount', type: 'DECIMAL' }], [{ amount: null }])[0].text, /数值指标为空/)
})

test('formats amounts and percentages and falls back on unsafe integers', () => {
  assert.equal(formatValue(0.125, 'percent'), '12.5%')
  assert.equal(formatValue(1234.5, 'currency'), '1,234.5')
  const columns = [{ name: 'name', type: 'VARCHAR' }, { name: 'value', type: 'BIGINT' }]
  const rows = [{ name: 'A', value: 9007199254740992 }]
  assert.equal(recommendChart(columns, rows).type, 'table')
  assert.equal(recommendChart([{ name: 'amount', type: 'DECIMAL' }], [{ amount: '90071992547409.92' }]).type, 'table')
  assert.match(buildInsights(columns, rows)[0].text, /安全比较精度/)
})

test('derives a decline and top category only from rows returned by SQL', () => {
  const regionColumns = [{ name: 'region_name', type: 'VARCHAR' }, { name: 'sales_change', type: 'DECIMAL' }]
  const regionRows = [{ region_name: '西部', sales_change: -12 }, { region_name: '北部', sales_change: -2 }]
  assert.match(buildInsights(regionColumns, regionRows, '哪个地区下降最多')[0].text, /西部/)
  const categoryColumns = [{ name: 'category_name', type: 'VARCHAR' }, { name: 'sales_amount', type: 'DECIMAL' }]
  const categoryRows = [{ category_name: 'A', sales_amount: 8 }, { category_name: 'B', sales_amount: 20 }, { category_name: 'C', sales_amount: 12 }, { category_name: 'D', sales_amount: 2 }]
  assert.match(buildInsights(categoryColumns, categoryRows)[0].text, /B.*C.*A/)
})

test('derives time trend and states empty or insufficient results safely', () => {
  const columns = [{ name: 'paid_month', type: 'VARCHAR' }, { name: 'paid_amount', type: 'DECIMAL' }]
  const rows = [{ paid_month: '2026-10', paid_amount: 80 }, { paid_month: '2026-09', paid_amount: 100 }]
  assert.match(buildInsights(columns, rows)[0].text, /2026-09.*2026-10.*下降/)
  assert.match(buildInsights(columns, [])[0].text, /结果为空/)
  assert.equal(recommendChart([{ name: 'name', type: 'VARCHAR' }], [{ name: 'x' }]).type, 'table')
})

test('finds the region with the greatest decline from grouped monthly query rows', () => {
  const columns = [{ name: 'region_name', type: 'VARCHAR' }, { name: 'paid_month', type: 'VARCHAR' }, { name: 'paid_amount', type: 'DECIMAL' }]
  const rows = [
    { region_name: '西部', paid_month: '2026-09', paid_amount: 100 },
    { region_name: '西部', paid_month: '2026-10', paid_amount: 30 },
    { region_name: '北部', paid_month: '2026-09', paid_amount: 100 },
    { region_name: '北部', paid_month: '2026-10', paid_amount: 90 },
  ]
  assert.match(buildInsights(columns, rows, '哪个地区销售额下降最多')[0].text, /西部.*下降.*70/)
  const growingRows = rows.map((row) => ({ ...row, paid_amount: row.paid_amount + (row.paid_month === '2026-10' ? 200 : 0) }))
  assert.match(buildInsights(columns, growingRows, '哪个地区销售额下降最多')[0].text, /没有地区销售额下降/)
})
