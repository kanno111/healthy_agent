<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCurrentAdmin } from '../api/agent'
import { logout as logoutRequest } from '../api/auth'
import {
  evaluateKnowledgeRetrieval,
  searchKnowledge,
  type KnowledgeRetrievalEvaluationResponse,
  type KnowledgeSearchResponse,
  type KnowledgeSearchStrategy
} from '../api/knowledge'
import { ApiError } from '../api/http'
import { session, signOut } from '../stores/session'

const router = useRouter()
const query = ref('')
const limit = ref(5)
const strategy = ref<KnowledgeSearchStrategy>('VECTOR')
const loading = ref(false)
const evaluationLoading = ref(false)
const checking = ref(true)
const loggingOut = ref(false)
const error = ref('')
const response = ref<KnowledgeSearchResponse | null>(null)
const evaluation = ref<KnowledgeRetrievalEvaluationResponse | null>(null)
const evaluationError = ref('')

const canSearch = computed(() => query.value.trim().length > 0 && query.value.trim().length <= 1000)

onMounted(async () => {
  try {
    const verified = await getCurrentAdmin(session.token)
    if (verified.role !== 'STAFF' || verified.userId !== session.userId) throw new Error('role mismatch')
  } catch {
    await leaveForLogin()
  } finally {
    checking.value = false
  }
})

async function search() {
  if (!canSearch.value || loading.value || checking.value) return
  loading.value = true
  error.value = ''
  response.value = null
  try {
    response.value = await searchKnowledge(
      session.token, query.value.trim(), limit.value, strategy.value)
  } catch (cause) {
    if (cause instanceof ApiError && (cause.status === 401 || cause.status === 403)) {
      await leaveForLogin()
      return
    }
    error.value = cause instanceof ApiError ? cause.message : '知识检索失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function runEvaluation() {
  if (evaluationLoading.value || checking.value) return
  evaluationLoading.value = true
  evaluationError.value = ''
  evaluation.value = null
  try {
    evaluation.value = await evaluateKnowledgeRetrieval(
      session.token, strategy.value, limit.value)
  } catch (cause) {
    if (cause instanceof ApiError && (cause.status === 401 || cause.status === 403)) {
      await leaveForLogin()
      return
    }
    evaluationError.value = cause instanceof ApiError ? cause.message : '检索评测失败，请稍后重试'
  } finally {
    evaluationLoading.value = false
  }
}

function useExample(value: string) {
  query.value = value
  response.value = null
  error.value = ''
}

async function logout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    await logoutRequest(session.token)
  } finally {
    await leaveForLogin()
    loggingOut.value = false
  }
}

async function leaveForLogin() {
  signOut()
  await router.replace('/admin/login')
}

function scoreText(score: number) {
  return Number.isFinite(score) ? score.toFixed(4) : '0.0000'
}

function percent(value: number) {
  return `${(value * 100).toFixed(1)}%`
}

function strategyName(value: KnowledgeSearchStrategy) {
  if (value === 'BM25') return 'BM25 关键词检索'
  if (value === 'HYBRID') return 'BM25 + Vector RRF'
  return 'BGE-M3 向量检索'
}

function loadingText(value: KnowledgeSearchStrategy) {
  if (value === 'BM25') return '正在执行 Elasticsearch BM25 全文检索…'
  if (value === 'HYBRID') return '正在执行 BM25、BGE-M3 Vector 与 RRF 排名融合…'
  return '正在调用 Embedding API 并执行 Elasticsearch kNN 检索…'
}

function scoreLabel(value: KnowledgeSearchStrategy) {
  return value === 'HYBRID' ? 'RRF 分' : '相关分'
}
</script>

<template>
  <main class="admin-page">
    <header class="topbar admin-topbar">
      <RouterLink class="brand dark brand-link" to="/admin">
        <span class="brand-mark" aria-hidden="true"><i></i><i></i></span>
        <span>Healthy Agent Console</span>
      </RouterLink>
      <div class="admin-account">
        <span>{{ session.name }} · 管理员</span>
        <button class="logout-button" :disabled="loggingOut" @click="logout">
          {{ loggingOut ? '正在退出…' : '退出登录' }}
        </button>
      </div>
    </header>

    <section class="admin-shell compact knowledge-page">
      <RouterLink class="back-link" to="/admin">← 返回管理首页</RouterLink>

      <div class="page-title">
        <p class="eyebrow mint">RETRIEVAL LAB</p>
        <h1>知识检索测试</h1>
        <p>使用同一问题对比 BGE-M3 Vector、Elasticsearch BM25 和 RRF 混合检索。</p>
      </div>

      <section class="search-panel" aria-labelledby="search-title">
        <div class="search-panel-heading">
          <div>
            <span class="step-label">管理员测试工具</span>
            <h2 id="search-title">测试问题</h2>
          </div>
          <div class="retrieval-options">
            <label class="limit-field">
              检索策略
              <select v-model="strategy" :disabled="loading || evaluationLoading">
                <option value="VECTOR">BGE-M3 Vector</option>
                <option value="BM25">Elasticsearch BM25</option>
                <option value="HYBRID">BM25 + Vector RRF</option>
              </select>
            </label>
            <label class="limit-field">
              返回数量
              <select v-model.number="limit" :disabled="loading || evaluationLoading">
                <option :value="3">Top 3</option>
                <option :value="5">Top 5</option>
                <option :value="10">Top 10</option>
                <option :value="20">Top 20</option>
              </select>
            </label>
          </div>
        </div>

        <form class="vector-search-form" @submit.prevent="search">
          <textarea
            v-model="query"
            maxlength="1000"
            :disabled="loading || checking"
            placeholder="例如：门诊预约取消后多久可以收到退款？"
            aria-label="检索问题"
          ></textarea>
          <div class="search-form-footer">
            <span>{{ query.trim().length }}/1000</span>
            <button class="primary-button search-button" :disabled="!canSearch || loading || checking">
              {{ loading ? '正在检索…' : '开始检索' }}
            </button>
          </div>
        </form>

        <div class="search-examples">
          <span>测试问题</span>
          <button @click="useExample('门诊预约取消后，费用什么时候退回？')">预约退费</button>
          <button @click="useExample('晚上突发胸痛应该去哪里就诊？')">急诊就医</button>
          <button @click="useExample('住院探视有哪些时间和人数限制？')">住院探视</button>
          <button @click="useExample('检验报告可以通过哪些方式领取？')">报告领取</button>
        </div>

        <p v-if="error" class="notice error-notice" role="alert">{{ error }}</p>
      </section>

      <section class="search-results" aria-live="polite">
        <div class="knowledge-toolbar">
          <div>
            <h2>召回结果</h2>
            <span v-if="response">{{ response.results.length }} 个 chunk</span>
            <span v-else>尚未检索</span>
          </div>
          <span v-if="response" class="storage-chip">
            {{ strategyName(response.strategy) }} · Top {{ response.limit }}
          </span>
        </div>

        <div v-if="loading" class="empty-state compact-state search-loading">
          <span class="loading-ring" aria-hidden="true"></span>
          <p>{{ loadingText(strategy) }}</p>
        </div>
        <div v-else-if="response && response.results.length === 0" class="empty-state compact-state">
          <span class="empty-icon" aria-hidden="true">检</span>
          <h2>没有可召回的 chunk</h2>
          <p>请先到“文档与知识库”上传文档并构建索引，然后重新测试。</p>
        </div>
        <div v-else-if="response" class="result-list">
          <article v-for="item in response.results" :key="item.chunkId" class="result-card">
            <div class="result-rank">{{ item.rank }}</div>
            <div class="result-body">
              <header>
                <div>
                  <strong>{{ item.fileName }}</strong>
                  <span>Chunk {{ item.chunkIndex + 1 }} · {{ item.chunkId }}</span>
                </div>
                <span class="score-chip">{{ scoreLabel(response.strategy) }} {{ scoreText(item.score) }}</span>
              </header>
              <p>{{ item.content }}</p>
            </div>
          </article>
        </div>
        <div v-else class="empty-state compact-state">
          <span class="empty-icon" aria-hidden="true">向</span>
          <h2>等待测试</h2>
          <p>检索结果会展示来源文档、chunk 序号、完整 chunk 文本和 Elasticsearch 相关分。</p>
        </div>
      </section>

      <section class="search-panel evaluation-panel" aria-labelledby="evaluation-title">
        <div class="search-panel-heading">
          <div>
            <span class="step-label">阶段 1 / 阶段 2 / 最小 RRF</span>
            <h2 id="evaluation-title">固定评测集</h2>
            <p>运行 104 条脱敏问题：96 条可回答问题（含 24 条跨制度多正例）和 8 条无答案或安全负例。</p>
          </div>
          <button
            class="primary-button"
            :disabled="evaluationLoading || loading || checking"
            @click="runEvaluation"
          >
            {{ evaluationLoading ? '正在评测…' : `运行 ${strategyName(strategy)} 基线` }}
          </button>
        </div>

        <p v-if="evaluationError" class="notice error-notice" role="alert">{{ evaluationError }}</p>

        <div v-if="evaluation" class="evaluation-summary">
          <div><span>Recall@{{ evaluation.limit }}</span><strong>{{ percent(evaluation.recallAtK) }}</strong></div>
          <div><span>HitRate@{{ evaluation.limit }}</span><strong>{{ percent(evaluation.hitRateAtK) }}</strong></div>
          <div><span>MRR</span><strong>{{ evaluation.mrr.toFixed(3) }}</strong></div>
          <div><span>nDCG@{{ evaluation.limit }}</span><strong>{{ evaluation.ndcgAtK.toFixed(3) }}</strong></div>
          <div><span>负例空召回率</span><strong>{{ percent(evaluation.negativeEmptyRate) }}</strong></div>
        </div>

        <details v-if="evaluation" class="evaluation-details">
          <summary>查看未在 Top {{ evaluation.limit }} 命中的正例</summary>
          <div class="evaluation-failures">
            <p
              v-for="item in evaluation.cases.filter(item => item.shouldAnswer && item.firstRelevantRank === 0)"
              :key="item.id"
            >
              <strong>{{ item.id }} · {{ item.category }}</strong>：{{ item.question }}
            </p>
            <p v-if="evaluation.cases.every(item => !item.shouldAnswer || item.firstRelevantRank > 0)">
              所有正例都在当前 Top K 内命中。
            </p>
          </div>
        </details>
      </section>

      <p class="search-scope-note">当前仅用于管理员验证召回质量，不调用大模型生成答案，也不区分知识发布状态；HitRate 表示正例问题是否至少命中一个标注文档。标准 Precision 仍由接口返回，但单目标问题固定取 Top 5 时理论上限通常只有 20%，不作为页面主指标。</p>
    </section>
  </main>
</template>
