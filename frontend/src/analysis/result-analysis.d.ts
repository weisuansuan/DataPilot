export interface AnalysisColumn { name: string; type?: string }
export interface MetricChoice { name: string; format: string }
export interface ChartRecommendation {
  type: 'table' | 'bar' | 'line' | 'pie' | 'kpi'
  reason?: string
  dimension?: string
  seriesDimension?: string
  seriesValues?: string[]
  categories?: string[]
  metric?: string
  format?: string
  metrics?: MetricChoice[]
}
export interface DataInsight { text: string; basis: string }
export function recommendChart(columns: AnalysisColumn[], rows: Record<string, unknown>[], question?: string): ChartRecommendation
export function buildChartOption(recommendation: ChartRecommendation, rows: Record<string, unknown>[]): Record<string, unknown> | null
export function buildInsights(columns: AnalysisColumn[], rows: Record<string, unknown>[], question?: string): DataInsight[]
export function formatValue(value: unknown, format?: string): string
export function metricFormat(column: AnalysisColumn): string
