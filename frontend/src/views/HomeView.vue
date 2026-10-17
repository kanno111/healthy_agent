<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCurrentPatient } from '../api/agent'
import { logout as logoutRequest } from '../api/auth'
import {
  confirmPatientAction,
  createPatientConversation,
  deletePatientProfile,
  deletePatientConversation,
  getPatientProfile,
  listPatientConversations,
  loadPatientConversationMessages,
  renamePatientConversation,
  rejectPatientAction,
  streamPatientMessage,
  updatePatientProfile,
  type ChatCitation,
  type ChatTokenUsage,
  type AgentTrace,
  type PatientChatResponse,
  type PatientActionResponse,
  type PatientActionStatus,
  type PendingPatientAction,
  type ConversationSummary,
  type ConversationHistoryMessage,
  type AppointmentPeriod,
  type ResponseStyle,
  type PatientProfile,
  type Sex,
  type SmokingStatus,
  type AlcoholStatus
} from '../api/chat'
import { ApiError } from '../api/http'
import { session, signOut } from '../stores/session'

type ChatMessage = {
  id: number
  role: 'user' | 'assistant'
  content: string
  welcome?: boolean
  citations?: ChatCitation[]
  model?: string
  usage?: ChatTokenUsage
  mode?: 'RAG' | 'TOOL' | 'CONFIRMATION_REQUIRED'
  tools?: string[]
  pendingAction?: PendingPatientAction | null
  actionStatus?: PatientActionStatus
  actionMessage?: string
  actionBusy?: boolean
  actionError?: string
  streaming?: boolean
  trace?: AgentTrace | null
  actionTrace?: AgentTrace | null
}

const router = useRouter()
const checking = ref(true)
const loggingOut = ref(false)
const sending = ref(false)
const input = ref('')
const error = ref('')
const chatViewport = ref<HTMLElement | null>(null)
const composerInput = ref<HTMLTextAreaElement | null>(null)
const conversations = ref<ConversationSummary[]>([])
const loadingConversation = ref(false)
const loadingOlder = ref(false)
const historyBefore = ref<number | null>(null)
const historyHasMore = ref(false)
const sidebarOpen = ref(false)
const profileOpen = ref(false)
const profileLoading = ref(false)
const profileSaving = ref(false)
const profileError = ref('')
const appointmentPeriod = ref<AppointmentPeriod>('NO_PREFERENCE')
const responseStyle = ref<ResponseStyle>('DEFAULT')
const age = ref('')
const sex = ref<Sex>('UNKNOWN')
const heightCm = ref('')
const weightKg = ref('')
const conditions = ref('')
const allergies = ref('')
const medications = ref('')
const smokingStatus = ref<SmokingStatus>('UNKNOWN')
const alcoholStatus = ref<AlcoholStatus>('UNKNOWN')
let nextMessageId = 1
let conversationId = ''

const createWelcomeMessage = (): ChatMessage => ({
  id: nextMessageId++,
  role: 'assistant',
  welcome: true,
  content: `你好，${session.name}。我可以查询科室、医生、号源以及你的预约和候补记录，也可以根据知识库回答医院制度问题。`
})

const messages = ref<ChatMessage[]>([createWelcomeMessage()])
const normalizedInput = computed(() => input.value.trim())
const canSend = computed(() => Boolean(conversationId)
  && normalizedInput.value.length > 0 && normalizedInput.value.length <= 1000)
const showSuggestions = computed(() => messages.value.length === 1 && !sending.value)

onMounted(async () => {
  try {
    const verified = await getCurrentPatient(session.token)
    if (verified.role !== 'PATIENT' || verified.userId !== session.userId) throw new Error('role mismatch')
  } catch {
    await leaveForLogin()
    return
  }
  try {
    await initializeConversations()
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '无法加载历史对话'
  } finally {
    checking.value = false
    await focusComposer()
  }
})

async function send(message?: string) {
  const content = (message ?? normalizedInput.value).trim()
  if (!content || content.length > 1000 || sending.value || checking.value) return

  const userMessageId = nextMessageId++
  appendMemoryMessage({ id: userMessageId, role: 'user', content })
  const assistantMessageId = nextMessageId++
  appendMemoryMessage({
    id: assistantMessageId,
    role: 'assistant',
    content: '',
    streaming: true
  })
  const assistantMessage = messages.value.find(message => message.id === assistantMessageId)!
  input.value = ''
  error.value = ''
  sending.value = true
  await scrollToBottom()
  await focusComposer()

  try {
    const result = await sendWithConversationRecovery(
      content,
      delta => {
        assistantMessage.content += delta
        void scrollToBottom()
      },
      trace => {
        assistantMessage.trace = trace
        void scrollToBottom()
      }
    )
    const reusedPendingCard = reconcileActionState(result)
    assistantMessage.content = result.answer
    assistantMessage.citations = result.citations
    assistantMessage.model = result.model
    assistantMessage.usage = result.usage
    assistantMessage.mode = result.mode
    assistantMessage.tools = result.tools
    assistantMessage.pendingAction = reusedPendingCard ? null : result.pendingAction
    assistantMessage.actionStatus = result.pendingAction?.status
    assistantMessage.trace = result.trace ?? assistantMessage.trace
    void refreshConversationList()
  } catch (cause) {
    if (cause instanceof ApiError && (cause.status === 401 || cause.status === 403)) {
      await leaveForLogin()
      return
    }
    if (!assistantMessage.content && !assistantMessage.trace) {
      messages.value = messages.value.filter(message => message.id !== assistantMessageId)
    } else if (!assistantMessage.content) {
      assistantMessage.content = '本次请求执行失败，请查看执行详情。'
    }
    error.value = cause instanceof ApiError ? cause.message : '消息发送失败，请稍后重试'
  } finally {
    assistantMessage.streaming = false
    sending.value = false
    await scrollToBottom()
    await focusComposer()
  }
}

function reconcileActionState(result: PatientChatResponse) {
  if (result.actionUpdate) {
    const existing = messages.value.find(message =>
      message.pendingAction?.actionId === result.actionUpdate?.actionId)
    if (existing) {
      existing.actionStatus = result.actionUpdate.status
      existing.actionMessage = result.actionUpdate.message
    }
  }
  if (!result.pendingAction) return false
  const existing = messages.value.find(message =>
    message.pendingAction?.actionId === result.pendingAction?.actionId)
  if (!existing) return false
  existing.pendingAction = result.pendingAction
  existing.actionStatus = result.pendingAction.status
  return true
}

async function sendWithConversationRecovery(
  content: string,
  onDelta: (content: string) => void,
  onTrace: (trace: AgentTrace) => void
) {
  return streamPatientMessage(session.token, conversationId, content, onDelta, onTrace)
}

function appendMemoryMessage(message: ChatMessage) {
  if (messages.value.length === 1 && messages.value[0]?.welcome) {
    messages.value = []
  }
  messages.value.push(message)
}

async function decideAction(message: ChatMessage, decision: 'confirm' | 'reject') {
  const action = message.pendingAction
  if (!action || message.actionBusy || message.actionStatus !== 'PENDING') return
  message.actionBusy = true
  message.actionError = ''
  try {
    const result: PatientActionResponse = decision === 'confirm'
      ? await confirmPatientAction(session.token, conversationId, action.actionId)
      : await rejectPatientAction(session.token, conversationId, action.actionId)
    message.actionStatus = result.status
    message.actionMessage = result.message
    message.actionTrace = result.trace
    void refreshConversationList()
  } catch (cause) {
    if (cause instanceof ApiError && (cause.status === 401 || cause.status === 403)) {
      await leaveForLogin()
      return
    }
    if (cause instanceof ApiError && (cause.code === 40910 || cause.code === 40911 || cause.code === 40420)) {
      message.actionStatus = cause.code === 40910 ? 'EXPIRED' : 'FAILED'
    }
    message.actionError = cause instanceof ApiError ? cause.message : '操作处理失败，请稍后重试'
  } finally {
    message.actionBusy = false
    await scrollToBottom()
    await focusComposer()
  }
}

function actionStatusLabel(status?: PatientActionStatus) {
  switch (status) {
    case 'PENDING': return '等待确认'
    case 'EXECUTING': return '正在执行'
    case 'SUCCEEDED': return '已完成'
    case 'REJECTED': return '已放弃'
    case 'EXPIRED': return '已过期'
    case 'FAILED': return '执行失败'
    default: return '未知状态'
  }
}

function displayValue(value: string | null | undefined) {
  return value?.trim() || '未提供'
}

function displayExpiry(value: string) {
  const expiresAt = new Date(value)
  if (Number.isNaN(expiresAt.getTime())) return '10 分钟内有效'
  return `请在 ${new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(expiresAt)} 前确认`
}

async function resetConversation() {
  if (sending.value) return
  try {
    const created = await createPatientConversation(session.token)
    conversations.value = [created, ...conversations.value]
    await selectConversation(created.conversationId)
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '新建对话失败'
  }
}

async function initializeConversations() {
  conversations.value = await listPatientConversations(session.token)
  if (!conversations.value.length) {
    conversations.value = [await createPatientConversation(session.token)]
  }
  await selectConversation(conversations.value[0]!.conversationId)
}

async function refreshConversationList() {
  try {
    conversations.value = await listPatientConversations(session.token)
  } catch {
    // 列表刷新失败不影响当前已经完成的聊天。
  }
}

async function selectConversation(id: string) {
  if (sending.value || loadingConversation.value) return
  loadingConversation.value = true
  error.value = ''
  try {
    const page = await loadPatientConversationMessages(session.token, id, null, 20)
    conversationId = id
    messages.value = page.messages.length
      ? page.messages.map(fromHistoryMessage)
      : [createWelcomeMessage()]
    historyBefore.value = page.nextBeforeSequence
    historyHasMore.value = page.hasMore
    sidebarOpen.value = false
    input.value = ''
    await scrollToBottom()
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '切换对话失败'
  } finally {
    loadingConversation.value = false
    await focusComposer()
  }
}

async function loadOlderMessages() {
  if (!conversationId || !historyHasMore.value || loadingOlder.value) return
  const viewport = chatViewport.value
  const previousHeight = viewport?.scrollHeight ?? 0
  loadingOlder.value = true
  try {
    const page = await loadPatientConversationMessages(
      session.token, conversationId, historyBefore.value, 20)
    const older = page.messages.map(fromHistoryMessage)
    messages.value = [...older, ...messages.value]
    historyBefore.value = page.nextBeforeSequence
    historyHasMore.value = page.hasMore
    await nextTick()
    if (viewport) viewport.scrollTop += viewport.scrollHeight - previousHeight
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '加载更早消息失败'
  } finally {
    loadingOlder.value = false
  }
}

function fromHistoryMessage(stored: ConversationHistoryMessage): ChatMessage {
  const response = stored.response
  const pending = response?.pendingAction ?? null
  let actionStatus = response?.actionUpdate?.status ?? pending?.status
  if (actionStatus === 'PENDING' && pending && new Date(pending.expiresAt).getTime() <= Date.now()) {
    actionStatus = 'EXPIRED'
  }
  return {
    id: nextMessageId++,
    role: stored.role === 'USER' ? 'user' : 'assistant',
    content: response?.answer ?? stored.content,
    citations: response?.citations,
    model: response?.model,
    usage: response?.usage,
    mode: response?.mode,
    tools: response?.tools,
    pendingAction: pending,
    actionStatus,
    actionMessage: response?.actionUpdate?.message,
    trace: null,
    actionTrace: null
  }
}

async function removeConversation(id: string) {
  if (sending.value || loadingConversation.value) return
  if (!window.confirm('删除后将无法恢复这段聊天记录，确定删除吗？')) return
  try {
    await deletePatientConversation(session.token, id)
    conversations.value = conversations.value.filter(item => item.conversationId !== id)
    if (conversationId === id) {
      if (!conversations.value.length) {
        conversations.value = [await createPatientConversation(session.token)]
      }
      await selectConversation(conversations.value[0]!.conversationId)
    }
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '删除对话失败'
  }
}

async function renameConversation(conversation: ConversationSummary) {
  if (sending.value || loadingConversation.value) return
  const proposed = window.prompt('请输入新的对话名称（最多 60 个字符）', conversation.title)
  if (proposed == null) return
  const title = proposed.replace(/[\r\n]+/g, ' ').trim()
  if (!title || Array.from(title).length > 60) {
    error.value = '对话名称必须为 1 至 60 个字符'
    return
  }
  if (title === conversation.title) return
  try {
    const updated = await renamePatientConversation(
      session.token, conversation.conversationId, title)
    conversations.value = conversations.value
      .map(item => item.conversationId === updated.conversationId ? updated : item)
      .sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt))
  } catch (cause) {
    error.value = cause instanceof ApiError ? cause.message : '修改对话名称失败'
  }
}

function formatConversationTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false
  }).format(date)
}

async function scrollToBottom() {
  await nextTick()
  if (chatViewport.value) chatViewport.value.scrollTop = chatViewport.value.scrollHeight
}

async function focusComposer() {
  await nextTick()
  composerInput.value?.focus({ preventScroll: true })
}

function handleComposerEnter(event: KeyboardEvent) {
  if (event.isComposing || sending.value) return
  event.preventDefault()
  void send()
}

function scoreText(score: number) {
  return Number.isFinite(score) ? score.toFixed(4) : '0.0000'
}

function traceTypeLabel(type: string) {
  switch (type) {
    case 'ROUTING': return '意图路由'
    case 'LLM': return 'LLM'
    case 'TOOL': return 'Tool'
    case 'RAG': return 'RAG'
    case 'HITL': return '人工确认'
    default: return type
  }
}

function traceDetailText(details: Record<string, unknown>) {
  const values: string[] = []
  if (details.model) values.push(`模型 ${String(details.model)}`)
  if (Array.isArray(details.routes)) values.push(`路由 ${details.routes.join(' + ')}`)
  if (details.strategy) values.push(`方式 ${String(details.strategy)}`)
  if (typeof details.totalTokens === 'number') values.push(`${details.totalTokens} Token`)
  if (typeof details.chunkCount === 'number') values.push(`${details.chunkCount} 个 Chunk`)
  if (typeof details.usedChunkCount === 'number') values.push(`使用 ${details.usedChunkCount} 个 Chunk`)
  if (typeof details.resultCount === 'number') values.push(`${details.resultCount} 条结果`)
  if (details.actionType) values.push(`操作 ${String(details.actionType)}`)
  if (details.actionStatus) values.push(`状态 ${String(details.actionStatus)}`)
  if (details.errorType) values.push(`错误 ${String(details.errorType)}`)
  return values.join(' · ')
}

function traceChunks(details: Record<string, unknown>) {
  if (!Array.isArray(details.chunks)) return []
  return details.chunks.filter((chunk): chunk is Record<string, unknown> =>
    typeof chunk === 'object' && chunk !== null)
}

function messageTraces(message: ChatMessage) {
  return [message.trace, message.actionTrace].filter((trace): trace is AgentTrace => Boolean(trace))
}

async function openProfile() {
  if (profileLoading.value || profileSaving.value) return
  profileOpen.value = true
  profileLoading.value = true
  profileError.value = ''
  try {
    const profile = await getPatientProfile(session.token)
    assignProfile(profile)
  } catch (cause) {
    profileError.value = cause instanceof ApiError ? cause.message : '无法加载用户画像'
  } finally {
    profileLoading.value = false
  }
}

function closeProfile() {
  if (!profileSaving.value) profileOpen.value = false
}

async function saveProfile() {
  if (profileSaving.value || profileLoading.value) return
  profileSaving.value = true
  profileError.value = ''
  try {
    const profile = await updatePatientProfile(session.token, {
      appointmentPeriod: appointmentPeriod.value,
      responseStyle: responseStyle.value,
      age: optionalNumber(age.value),
      sex: sex.value,
      heightCm: optionalNumber(heightCm.value),
      weightKg: optionalNumber(weightKg.value),
      conditions: parseProfileList(conditions.value),
      allergies: parseProfileList(allergies.value),
      medications: parseProfileList(medications.value),
      smokingStatus: smokingStatus.value,
      alcoholStatus: alcoholStatus.value
    })
    assignProfile(profile)
    profileOpen.value = false
  } catch (cause) {
    profileError.value = cause instanceof ApiError ? cause.message : '保存用户画像失败'
  } finally {
    profileSaving.value = false
  }
}

async function clearProfile() {
  if (profileSaving.value || profileLoading.value) return
  if (!window.confirm('确定清除全部用户画像，包括健康信息和偏好吗？')) return
  profileSaving.value = true
  profileError.value = ''
  try {
    await deletePatientProfile(session.token)
    assignProfile(emptyProfile())
    profileOpen.value = false
  } catch (cause) {
    profileError.value = cause instanceof ApiError ? cause.message : '清除用户画像失败'
  } finally {
    profileSaving.value = false
  }
}

function assignProfile(profile: PatientProfile) {
  appointmentPeriod.value = profile.appointmentPeriod
  responseStyle.value = profile.responseStyle
  age.value = profile.age == null ? '' : String(profile.age)
  sex.value = profile.sex
  heightCm.value = profile.heightCm == null ? '' : String(profile.heightCm)
  weightKg.value = profile.weightKg == null ? '' : String(profile.weightKg)
  conditions.value = profile.conditions.join('\n')
  allergies.value = profile.allergies.join('\n')
  medications.value = profile.medications.join('\n')
  smokingStatus.value = profile.smokingStatus
  alcoholStatus.value = profile.alcoholStatus
}

function emptyProfile(): PatientProfile {
  return {
    appointmentPeriod: 'NO_PREFERENCE',
    responseStyle: 'DEFAULT',
    age: null,
    sex: 'UNKNOWN',
    heightCm: null,
    weightKg: null,
    conditions: [],
    allergies: [],
    medications: [],
    smokingStatus: 'UNKNOWN',
    alcoholStatus: 'UNKNOWN',
    updatedAt: null
  }
}

function optionalNumber(value: string) {
  const normalized = value.trim()
  return normalized ? Number(normalized) : null
}

function parseProfileList(value: string) {
  return [...new Set(value.split(/[\n,，]/)
    .map(item => item.trim())
    .filter(Boolean))]
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
  await router.replace('/login')
}
</script>

<template>
  <main class="patient-chat-page">
    <header class="topbar patient-chat-topbar">
      <div class="brand dark">
        <span class="brand-mark" aria-hidden="true"><i></i><i></i></span>
        <span>Healthy Agent</span>
      </div>
      <div class="patient-chat-actions">
        <button
          class="conversation-toggle"
          :class="{ active: sidebarOpen }"
          @click="sidebarOpen = !sidebarOpen"
        >会话</button>
        <span class="patient-name">{{ session.name }}</span>
        <button class="profile-button" :disabled="profileSaving" @click="openProfile">画像</button>
        <button class="new-chat-button" :disabled="sending" @click="resetConversation">新对话</button>
        <button class="logout-button" :disabled="loggingOut" @click="logout">
          {{ loggingOut ? '正在退出…' : '退出登录' }}
        </button>
      </div>
    </header>

    <div class="patient-chat-layout">
      <aside class="conversation-sidebar" :class="{ open: sidebarOpen }">
        <header>
          <div>
            <strong>历史对话</strong>
            <small>{{ conversations.length }} 个会话</small>
          </div>
          <button :disabled="sending" aria-label="新建对话" @click="resetConversation">＋</button>
        </header>
        <div class="conversation-list">
          <article
            v-for="conversation in conversations"
            :key="conversation.conversationId"
            :class="{ active: conversation.conversationId === conversationId }"
          >
            <button
              class="conversation-select"
              :disabled="sending || loadingConversation"
              :title="conversation.title"
              @click="selectConversation(conversation.conversationId)"
            >
              <strong>{{ conversation.title }}</strong>
              <small>{{ formatConversationTime(conversation.updatedAt) }}</small>
            </button>
            <div class="conversation-actions">
              <button
                class="conversation-rename"
                :disabled="sending || loadingConversation"
                aria-label="修改对话名称"
                title="修改名称"
                @click="renameConversation(conversation)"
              >✎</button>
              <button
                class="conversation-delete"
                :disabled="sending || loadingConversation"
                aria-label="删除对话"
                title="删除对话"
                @click="removeConversation(conversation.conversationId)"
              >×</button>
            </div>
          </article>
        </div>
      </aside>
      <button
        v-if="sidebarOpen"
        class="conversation-backdrop"
        aria-label="关闭会话列表"
        @click="sidebarOpen = false"
      ></button>

      <section class="patient-chat-shell">
      <header class="chat-intro">
        <div>
          <span class="chat-status"><i></i>{{ checking ? '正在验证身份' : '预约助手在线' }}</span>
          <h1>医院智能助手</h1>
          <p>支持知识库问答、业务查询，以及需要患者明确确认的预约和候补操作。</p>
        </div>
        <span class="chat-mode-chip">RAG + Tools + 人工确认</span>
      </header>

      <div ref="chatViewport" class="chat-viewport" aria-live="polite">
        <button
          v-if="historyHasMore"
          class="load-older-button"
          :disabled="loadingOlder"
          @click="loadOlderMessages"
        >{{ loadingOlder ? '正在加载…' : '加载更早消息' }}</button>
        <div class="message-stream">
          <article
            v-for="message in messages"
            :key="message.id"
            class="chat-message"
            :class="`chat-message-${message.role}`"
          >
            <div class="message-avatar" aria-hidden="true">{{ message.role === 'assistant' ? 'AI' : '我' }}</div>
            <div class="message-column">
              <div v-if="message.streaming && !message.content" class="message-bubble typing-bubble">
                <span></span><span></span><span></span>
                <em>正在判断问题并查询所需数据</em>
              </div>
              <div v-else class="message-bubble" :class="{ streaming: message.streaming }">{{ message.content }}</div>

              <div v-if="message.role === 'assistant' && message.citations?.length" class="message-sources">
                <span class="source-heading">回答依据</span>
                <details v-for="citation in message.citations" :key="citation.chunkId" class="patient-source-card">
                  <summary>
                    <span>[资料{{ citation.reference }}]</span>
                    <strong>{{ citation.fileName }}</strong>
                    <small>{{ scoreText(citation.score) }}</small>
                  </summary>
                  <p>{{ citation.content }}</p>
                </details>
              </div>

              <section
                v-if="message.role === 'assistant' && message.pendingAction"
                class="patient-action-card"
                :class="`action-${(message.actionStatus || 'PENDING').toLowerCase()}`"
              >
                <header>
                  <div>
                    <span>HUMAN CONFIRMATION</span>
                    <strong>{{ message.pendingAction.preview.title }}</strong>
                  </div>
                  <em>{{ actionStatusLabel(message.actionStatus) }}</em>
                </header>
                <p class="action-description">{{ message.pendingAction.preview.description }}</p>
                <dl>
                  <div
                    v-for="field in message.pendingAction.preview.fields"
                    :key="field.key"
                  >
                    <dt>{{ field.label }}</dt>
                    <dd>{{ displayValue(field.value) }}</dd>
                  </div>
                </dl>
                <p v-if="message.actionStatus === 'PENDING'" class="action-expiry">
                  {{ displayExpiry(message.pendingAction.expiresAt) }}，超时后必须重新发起。
                </p>
                <p v-if="message.actionMessage" class="action-result">{{ message.actionMessage }}</p>
                <p v-if="message.actionError" class="action-error">{{ message.actionError }}</p>
                <div v-if="message.actionStatus === 'PENDING'" class="action-buttons">
                  <button
                    class="action-reject"
                    :disabled="message.actionBusy"
                    @click="decideAction(message, 'reject')"
                  >{{ message.pendingAction.preview.rejectButtonText }}</button>
                  <button
                    class="action-confirm"
                    :disabled="message.actionBusy"
                    @click="decideAction(message, 'confirm')"
                  >{{ message.actionBusy ? '正在处理…' : message.pendingAction.preview.confirmButtonText }}</button>
                </div>
              </section>

              <div v-if="message.role === 'assistant' && message.usage" class="message-meta">
                <span>{{ message.mode === 'RAG' ? '知识库回答' : message.mode === 'CONFIRMATION_REQUIRED' ? '等待人工确认' : '实时业务查询' }}</span>
                <span>{{ message.model }}</span>
                <span>{{ message.usage.totalTokens }} Token</span>
              </div>
              <div v-if="message.role === 'assistant' && message.tools?.length" class="message-tools">
                <span v-for="tool in message.tools" :key="tool">{{ tool }}</span>
              </div>
              <div
                v-if="message.role === 'assistant' && messageTraces(message).length"
                class="agent-trace-stack"
              >
                <details
                  v-for="(trace, traceIndex) in messageTraces(message)"
                  :key="trace.turnId"
                  class="agent-trace"
                >
                  <summary>
                    <span>{{ traceIndex === 0 ? '查看执行详情' : '查看确认操作详情' }}</span>
                    <small>{{ trace.steps.length }} 个步骤 · {{ trace.totalDurationMs }}ms</small>
                  </summary>
                  <div class="agent-trace-body">
                    <header>
                      <span>Turn {{ trace.turnId.slice(0, 8) }}</span>
                      <strong>总耗时 {{ trace.totalDurationMs }}ms</strong>
                    </header>
                    <ol>
                      <li
                        v-for="step in trace.steps"
                        :key="`${trace.turnId}-${step.sequence}`"
                        :class="`trace-${step.status.toLowerCase()}`"
                      >
                        <i aria-hidden="true"></i>
                        <div>
                          <header>
                            <span>{{ traceTypeLabel(step.type) }}</span>
                            <strong>{{ step.name }}</strong>
                            <b>{{ step.status === 'SUCCESS' ? '成功' : '失败' }}</b>
                            <em>{{ step.durationMs }}ms</em>
                          </header>
                          <p>{{ step.summary }}</p>
                          <small v-if="traceDetailText(step.details)">
                            {{ traceDetailText(step.details) }}
                          </small>
                          <ul v-if="traceChunks(step.details).length" class="trace-chunks">
                            <li
                              v-for="chunk in traceChunks(step.details)"
                              :key="String(chunk.chunkId)"
                            >
                              {{ String(chunk.source) }} · {{ String(chunk.chunkId) }}
                            </li>
                          </ul>
                        </div>
                      </li>
                    </ol>
                  </div>
                </details>
              </div>
            </div>
          </article>

        </div>

        <div v-if="showSuggestions" class="patient-suggestions">
          <span>你可以这样问</span>
          <div>
            <button @click="send('帮我查询一下我的预约记录')">查询我的预约</button>
            <button @click="send('帮我取消最近一条可取消的预约')">取消预约</button>
            <button @click="send('帮我查询下周有号的医生并预约')">创建预约</button>
            <button @click="send('查询我的候补记录')">查询我的候补</button>
            <button @click="send('医院有哪些科室？')">查询医院科室</button>
            <button @click="send('门诊预约取消后如何退费？')">门诊取消后如何退费？</button>
            <button @click="send('晚上突然持续胸痛应该怎么办？')">夜间胸痛怎么办？</button>
          </div>
        </div>
      </div>

      <footer class="chat-composer-wrap">
        <p v-if="error" class="chat-error" role="alert">{{ error }}</p>
        <form class="chat-composer" @submit.prevent="send()">
          <textarea
            ref="composerInput"
            v-model="input"
            maxlength="1000"
            rows="1"
            :disabled="checking"
            placeholder="查询科室、医生、号源、预约或候补，并办理需要确认的操作…"
            aria-label="聊天消息"
            @keydown.enter.exact="handleComposerEnter"
          ></textarea>
          <button type="submit" :disabled="!canSend || sending || checking" aria-label="发送消息">
            <span aria-hidden="true">↑</span>
          </button>
        </form>
        <div class="composer-hint">
          <span>Enter 发送 · Shift + Enter 换行</span>
          <span>{{ normalizedInput.length }}/1000</span>
        </div>
        <p class="medical-disclaimer">完整聊天记录保存在 Redis；模型每次只读取当前对话最近 20 条消息。回答不能替代医生诊断；紧急情况请立即拨打 120。</p>
      </footer>
      </section>
    </div>

    <div v-if="profileOpen" class="modal-backdrop" @click.self="closeProfile">
      <section class="modal-card profile-modal" role="dialog" aria-modal="true" aria-labelledby="profile-title">
        <header class="modal-header">
          <div>
            <span>USER PROFILE</span>
            <h2 id="profile-title">我的用户画像</h2>
          </div>
          <button class="modal-close" :disabled="profileSaving" aria-label="关闭" @click="closeProfile">×</button>
        </header>
        <div v-if="profileLoading" class="profile-loading">正在加载画像…</div>
        <form v-else class="profile-form" @submit.prevent="saveProfile">
          <p>这些信息跨对话生效。健康信息属于你的自述，不是医院确认病历，也不会替代 Healthy 的实时数据。</p>
          <section class="profile-section">
            <h3>使用偏好</h3>
            <div class="profile-grid">
              <label>
                <span>预约时段偏好</span>
                <select v-model="appointmentPeriod" :disabled="profileSaving">
                  <option value="NO_PREFERENCE">无偏好</option>
                  <option value="MORNING">上午</option>
                  <option value="AFTERNOON">下午</option>
                </select>
              </label>
              <label>
                <span>回答风格</span>
                <select v-model="responseStyle" :disabled="profileSaving">
                  <option value="DEFAULT">默认</option>
                  <option value="CONCISE">简洁</option>
                  <option value="DETAILED">详细</option>
                </select>
              </label>
            </div>
          </section>
          <section class="profile-section">
            <h3>基本健康信息</h3>
            <div class="profile-grid profile-grid-three">
              <label>
                <span>年龄</span>
                <input v-model="age" type="number" min="0" max="150" placeholder="未填写" :disabled="profileSaving">
              </label>
              <label>
                <span>生理性别</span>
                <select v-model="sex" :disabled="profileSaving">
                  <option value="UNKNOWN">未填写</option>
                  <option value="MALE">男</option>
                  <option value="FEMALE">女</option>
                </select>
              </label>
              <label>
                <span>身高（厘米）</span>
                <input v-model="heightCm" type="number" min="30" max="250" placeholder="未填写" :disabled="profileSaving">
              </label>
              <label>
                <span>体重（千克）</span>
                <input v-model="weightKg" type="number" min="1" max="500" step="0.1" placeholder="未填写" :disabled="profileSaving">
              </label>
              <label>
                <span>吸烟情况</span>
                <select v-model="smokingStatus" :disabled="profileSaving">
                  <option value="UNKNOWN">未填写</option>
                  <option value="NEVER">从不吸烟</option>
                  <option value="FORMER">已戒烟</option>
                  <option value="CURRENT">目前吸烟</option>
                </select>
              </label>
              <label>
                <span>饮酒情况</span>
                <select v-model="alcoholStatus" :disabled="profileSaving">
                  <option value="UNKNOWN">未填写</option>
                  <option value="NEVER">从不饮酒</option>
                  <option value="FORMER">已戒酒</option>
                  <option value="CURRENT">目前饮酒</option>
                </select>
              </label>
            </div>
          </section>
          <section class="profile-section">
            <h3>健康情况</h3>
            <p class="profile-section-hint">每行填写一项，每类最多 10 项；只填写本人明确知道的信息。</p>
            <div class="profile-grid">
              <label>
                <span>已确诊疾病 / 慢性病</span>
                <textarea v-model="conditions" rows="4" placeholder="例如：高血压&#10;2型糖尿病" :disabled="profileSaving"></textarea>
              </label>
              <label>
                <span>过敏史</span>
                <textarea v-model="allergies" rows="4" placeholder="例如：青霉素&#10;花生" :disabled="profileSaving"></textarea>
              </label>
              <label class="profile-wide-field">
                <span>当前用药</span>
                <textarea v-model="medications" rows="4" placeholder="例如：二甲双胍 500mg 每日2次" :disabled="profileSaving"></textarea>
              </label>
            </div>
          </section>
          <p v-if="profileError" class="profile-error" role="alert">{{ profileError }}</p>
          <div class="profile-actions">
            <button type="button" class="profile-clear" :disabled="profileSaving" @click="clearProfile">清除全部画像</button>
            <button type="submit" class="profile-save" :disabled="profileSaving">
              {{ profileSaving ? '正在保存…' : '保存' }}
            </button>
          </div>
        </form>
      </section>
    </div>
  </main>
</template>
