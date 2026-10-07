<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getCurrentAdmin } from '../api/agent'
import { logout as logoutRequest } from '../api/auth'
import {
  buildKnowledgeDocumentIndex,
  deleteKnowledgeDocument,
  listKnowledgeDocuments,
  previewKnowledgeDocument,
  uploadKnowledgeDocument,
  type KnowledgeDocument,
  type KnowledgeDocumentPreview
} from '../api/knowledge'
import { ApiError } from '../api/http'
import { session, signOut } from '../stores/session'

const MAX_FILE_SIZE = 20 * 1024 * 1024
const ALLOWED_EXTENSIONS = new Set(['pdf', 'docx', 'txt', 'md', 'markdown'])

const router = useRouter()
const fileInput = ref<HTMLInputElement | null>(null)
const documents = ref<KnowledgeDocument[]>([])
const selectedFile = ref<File | null>(null)
const loading = ref(true)
const uploading = ref(false)
const loggingOut = ref(false)
const dragging = ref(false)
const error = ref('')
const success = ref('')
const operationError = ref('')
const previewTarget = ref<KnowledgeDocument | null>(null)
const previewResult = ref<KnowledgeDocumentPreview | null>(null)
const previewLoadingId = ref('')
const deleteTarget = ref<KnowledgeDocument | null>(null)
const deletingId = ref('')
const indexingId = ref('')

const documentCountText = computed(() => `${documents.value.length} 个文档`)

onMounted(async () => {
  try {
    const verified = await getCurrentAdmin(session.token)
    if (verified.role !== 'STAFF' || verified.userId !== session.userId) {
      throw new Error('role mismatch')
    }
    documents.value = await listKnowledgeDocuments(session.token)
  } catch (cause) {
    if (cause instanceof ApiError && cause.status !== 401 && cause.status !== 403) {
      error.value = cause.message
    } else {
      await leaveForLogin()
    }
  } finally {
    loading.value = false
  }
})

function openFilePicker() {
  if (!uploading.value) fileInput.value?.click()
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  selectFile(input.files?.[0] ?? null)
  input.value = ''
}

function onDrop(event: DragEvent) {
  dragging.value = false
  selectFile(event.dataTransfer?.files?.[0] ?? null)
}

function selectFile(file: File | null) {
  error.value = ''
  success.value = ''
  if (!file) return

  const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
  if (!ALLOWED_EXTENSIONS.has(extension)) {
    selectedFile.value = null
    error.value = '仅支持 PDF、DOCX、TXT、MD 和 Markdown 文件'
    return
  }
  if (file.size > MAX_FILE_SIZE) {
    selectedFile.value = null
    error.value = '单个文档不能超过 20 MiB'
    return
  }
  if (file.size === 0) {
    selectedFile.value = null
    error.value = '不能上传空文件'
    return
  }
  selectedFile.value = file
}

async function upload() {
  if (!selectedFile.value || uploading.value) return
  uploading.value = true
  error.value = ''
  success.value = ''
  const fileName = selectedFile.value.name
  try {
    const uploaded = await uploadKnowledgeDocument(session.token, selectedFile.value)
    documents.value = [uploaded, ...documents.value.filter(item => item.id !== uploaded.id)]
    selectedFile.value = null
    success.value = `“${fileName}”已保存到 MinIO，并登记到文档列表`
  } catch (cause) {
    if (cause instanceof ApiError && (cause.status === 401 || cause.status === 403)) {
      await leaveForLogin()
      return
    }
    error.value = cause instanceof ApiError ? cause.message : '文档上传失败，请稍后重试'
  } finally {
    uploading.value = false
  }
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

async function openPreview(document: KnowledgeDocument) {
  previewTarget.value = document
  previewResult.value = null
  previewLoadingId.value = document.id
  operationError.value = ''
  try {
    previewResult.value = await previewKnowledgeDocument(session.token, document.id)
  } catch (cause) {
    if (cause instanceof ApiError && (cause.status === 401 || cause.status === 403)) {
      await leaveForLogin()
      return
    }
    const message = cause instanceof ApiError ? cause.message : '文档解析失败，请稍后重试'
    operationError.value = message
    previewTarget.value = null
  } finally {
    previewLoadingId.value = ''
  }
}

function closePreview() {
  if (!previewLoadingId.value) {
    previewTarget.value = null
    previewResult.value = null
  }
}

async function confirmDelete() {
  const document = deleteTarget.value
  if (!document || deletingId.value) return
  deletingId.value = document.id
  operationError.value = ''
  success.value = ''
  try {
    await deleteKnowledgeDocument(session.token, document.id)
    documents.value = documents.value.filter(item => item.id !== document.id)
    deleteTarget.value = null
    success.value = `“${document.fileName}”已删除`
  } catch (cause) {
    if (cause instanceof ApiError && (cause.status === 401 || cause.status === 403)) {
      await leaveForLogin()
      return
    }
    operationError.value = cause instanceof ApiError ? cause.message : '文档删除失败，请稍后重试'
  } finally {
    deletingId.value = ''
  }
}

async function buildIndex(document: KnowledgeDocument) {
  if (indexingId.value) return
  indexingId.value = document.id
  operationError.value = ''
  success.value = ''
  try {
    const result = await buildKnowledgeDocumentIndex(session.token, document.id, document.indexed)
    documents.value = documents.value.map(item => item.id === document.id
      ? {
          ...item,
          indexed: result.indexed,
          chunkCount: result.chunkCount,
          indexedAt: result.indexedAt
        }
      : item)
    success.value = result.skipped
      ? `“${document.fileName}”已经构建过索引`
      : `“${document.fileName}”已切分为 ${result.chunkCount} 个 chunk，生成 BGE-M3 向量并写入 Elasticsearch`
  } catch (cause) {
    if (cause instanceof ApiError && (cause.status === 401 || cause.status === 403)) {
      await leaveForLogin()
      return
    }
    operationError.value = cause instanceof ApiError ? cause.message : '文档索引构建失败，请稍后重试'
  } finally {
    indexingId.value = ''
  }
}

async function leaveForLogin() {
  signOut()
  await router.replace('/admin/login')
}

function formatSize(size: number) {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KiB`
  return `${(size / 1024 / 1024).toFixed(1)} MiB`
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false
  }).format(new Date(value))
}

function fileType(fileName: string) {
  return fileName.split('.').pop()?.toUpperCase() ?? 'FILE'
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
        <p class="eyebrow mint">KNOWLEDGE DOCUMENTS</p>
        <h1>文档与知识库</h1>
        <p>上传医院制度和通用医疗知识，可预览文本、切分 chunk，并使用 BGE-M3 生成向量写入 Elasticsearch。</p>
      </div>

      <section class="upload-panel" aria-labelledby="upload-title">
        <div class="upload-copy">
          <span class="step-label">第一阶段</span>
          <h2 id="upload-title">上传原始文档</h2>
          <p>支持 PDF、DOCX、TXT、MD、Markdown，单个文件最大 20 MiB。</p>
        </div>

        <div
          class="drop-zone"
          :class="{ dragging, selected: selectedFile }"
          role="button"
          tabindex="0"
          @click="openFilePicker"
          @keydown.enter.prevent="openFilePicker"
          @keydown.space.prevent="openFilePicker"
          @dragover.prevent="dragging = true"
          @dragleave.prevent="dragging = false"
          @drop.prevent="onDrop"
        >
          <input
            ref="fileInput"
            class="visually-hidden"
            type="file"
            accept=".pdf,.docx,.txt,.md,.markdown"
            @change="onFileChange"
          />
          <span class="upload-icon" aria-hidden="true">↑</span>
          <template v-if="selectedFile">
            <strong>{{ selectedFile.name }}</strong>
            <span>{{ formatSize(selectedFile.size) }} · 点击重新选择</span>
          </template>
          <template v-else>
            <strong>拖放文件到这里，或点击选择</strong>
            <span>上传前会验证扩展名、大小和文件特征</span>
          </template>
        </div>

        <div v-if="selectedFile" class="upload-confirmation">
          <div>
            <span>即将上传</span>
            <strong>{{ selectedFile.name }}</strong>
          </div>
          <button class="secondary-button" :disabled="uploading" @click="selectedFile = null">取消</button>
          <button class="primary-button" :disabled="uploading" @click="upload">
            {{ uploading ? '正在上传…' : '确认上传' }}
          </button>
        </div>

        <p v-if="error" class="notice error-notice" role="alert">{{ error }}</p>
        <p v-if="success" class="notice success-notice" role="status">{{ success }}</p>
      </section>

      <section class="document-section" aria-labelledby="document-list-title">
        <div class="knowledge-toolbar">
          <div>
            <h2 id="document-list-title">已上传文档</h2>
            <span>{{ loading ? '正在读取…' : documentCountText }}</span>
          </div>
          <span class="storage-chip">MySQL 文档 · MinIO 原文件 · ES chunks + vectors</span>
        </div>

        <p v-if="operationError" class="notice error-notice operation-notice" role="alert">
          {{ operationError }}
        </p>

        <div v-if="loading" class="empty-state compact-state">
          <span class="loading-ring" aria-hidden="true"></span>
          <p>正在读取文档列表…</p>
        </div>
        <div v-else-if="documents.length === 0" class="empty-state">
          <span class="empty-icon" aria-hidden="true">文</span>
          <h2>还没有上传文档</h2>
          <p>选择第一份医院制度或医疗知识文档。上传成功后，原文件保存到 MinIO，文档信息登记到 MySQL。</p>
        </div>
        <div v-else class="document-list">
          <article v-for="document in documents" :key="document.id" class="document-row">
            <span class="file-badge">{{ fileType(document.fileName) }}</span>
            <div class="document-main">
              <strong :title="document.fileName">{{ document.fileName }}</strong>
              <span>{{ formatSize(document.size) }} · {{ formatDate(document.uploadedAt) }}</span>
            </div>
            <div class="document-state-group">
              <span class="document-status"><i></i>已上传</span>
              <span class="index-status" :class="{ indexed: document.indexed }">
                {{ document.indexed ? `已索引 · ${document.chunkCount} chunks` : '未构建索引' }}
              </span>
            </div>
            <div class="document-actions">
              <button
                class="text-action index-action"
                :disabled="Boolean(indexingId) || previewLoadingId === document.id || deletingId === document.id"
                @click="buildIndex(document)"
              >
                {{ indexingId === document.id ? '构建中…' : (document.indexed ? '重新构建' : '构建索引') }}
              </button>
              <button
                class="text-action"
                :disabled="Boolean(indexingId) || previewLoadingId === document.id || deletingId === document.id"
                @click="openPreview(document)"
              >
                {{ previewLoadingId === document.id ? '解析中…' : '解析预览' }}
              </button>
              <button
                class="text-action danger-action"
                :disabled="Boolean(indexingId) || deletingId === document.id || previewLoadingId === document.id"
                @click="deleteTarget = document"
              >删除</button>
            </div>
          </article>
        </div>
      </section>
    </section>

    <div v-if="previewTarget" class="modal-backdrop" @click.self="closePreview">
      <section class="modal-card preview-modal" role="dialog" aria-modal="true" aria-labelledby="preview-title">
        <header class="modal-header">
          <div>
            <span>解析预览</span>
            <h2 id="preview-title">{{ previewTarget.fileName }}</h2>
          </div>
          <button class="modal-close" :disabled="Boolean(previewLoadingId)" aria-label="关闭" @click="closePreview">×</button>
        </header>
        <div v-if="previewLoadingId" class="preview-loading">
          <span class="loading-ring" aria-hidden="true"></span>
          <p>正在从 MinIO 读取并解析文档…</p>
        </div>
        <template v-else-if="previewResult">
          <div class="preview-meta">
            <span>已提取 {{ previewResult.extractedCharacters.toLocaleString() }} 个字符</span>
            <span v-if="previewResult.truncated" class="truncated-chip">仅展示前 {{ previewResult.previewCharacters.toLocaleString() }} 个字符</span>
            <span v-else>完整预览</span>
          </div>
          <pre class="preview-text">{{ previewResult.preview }}</pre>
        </template>
      </section>
    </div>

    <div v-if="deleteTarget" class="modal-backdrop" @click.self="!deletingId && (deleteTarget = null)">
      <section class="modal-card delete-modal" role="alertdialog" aria-modal="true" aria-labelledby="delete-title">
        <span class="delete-symbol" aria-hidden="true">!</span>
        <h2 id="delete-title">确认删除文档？</h2>
        <p>“{{ deleteTarget.fileName }}”将从 MySQL、MinIO 和 Elasticsearch 中删除，此操作无法撤销。</p>
        <div class="modal-actions">
          <button class="secondary-button" :disabled="Boolean(deletingId)" @click="deleteTarget = null">取消</button>
          <button class="delete-button" :disabled="Boolean(deletingId)" @click="confirmDelete">
            {{ deletingId ? '正在删除…' : '确认删除' }}
          </button>
        </div>
      </section>
    </div>
  </main>
</template>
