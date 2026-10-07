import { ApiError, readApiResponse } from './http'

export type KnowledgeDocument = {
  id: string
  fileName: string
  size: number
  contentType: string
  status: 'UPLOADED'
  indexed: boolean
  chunkCount: number
  indexedAt: string | null
  uploadedAt: string
}

export type KnowledgeDocumentPreview = {
  documentId: string
  fileName: string
  contentType: string
  preview: string
  extractedCharacters: number
  previewCharacters: number
  truncated: boolean
}

export type KnowledgeIndexResult = {
  documentId: string
  indexed: boolean
  chunkCount: number
  indexedAt: string | null
  skipped: boolean
}

export type KnowledgeSearchHit = {
  rank: number
  chunkId: string
  documentId: string
  fileName: string
  contentType: string
  chunkIndex: number
  content: string
  score: number
}

export type KnowledgeSearchResponse = {
  query: string
  embeddingModel: string
  limit: number
  results: KnowledgeSearchHit[]
}

const DOCUMENT_ENDPOINT = '/api/agent/admin/knowledge/documents'

export async function listKnowledgeDocuments(token: string): Promise<KnowledgeDocument[]> {
  let response: Response
  try {
    response = await fetch(DOCUMENT_ENDPOINT, {
      headers: { Authorization: `Bearer ${token}` }
    })
  } catch {
    throw new ApiError('无法连接到文档服务，请确认 Gateway 和 Agent 已启动')
  }
  return readApiResponse(response, '读取文档列表失败')
}

export async function uploadKnowledgeDocument(
  token: string,
  file: File
): Promise<KnowledgeDocument> {
  const form = new FormData()
  form.append('file', file)

  let response: Response
  try {
    response = await fetch(DOCUMENT_ENDPOINT, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: form
    })
  } catch {
    throw new ApiError('无法连接到文档服务，请稍后重试')
  }
  return readApiResponse(response, '文档上传失败')
}

export async function previewKnowledgeDocument(
  token: string,
  documentId: string
): Promise<KnowledgeDocumentPreview> {
  let response: Response
  try {
    response = await fetch(`${DOCUMENT_ENDPOINT}/${encodeURIComponent(documentId)}/parse-preview`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` }
    })
  } catch {
    throw new ApiError('无法连接到文档解析服务，请稍后重试')
  }
  return readApiResponse(response, '文档解析失败')
}

export async function deleteKnowledgeDocument(token: string, documentId: string): Promise<void> {
  let response: Response
  try {
    response = await fetch(`${DOCUMENT_ENDPOINT}/${encodeURIComponent(documentId)}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` }
    })
  } catch {
    throw new ApiError('无法连接到文档服务，请稍后重试')
  }
  await readApiResponse<null>(response, '文档删除失败')
}

export async function buildKnowledgeDocumentIndex(
  token: string,
  documentId: string,
  force: boolean
): Promise<KnowledgeIndexResult> {
  let response: Response
  try {
    const query = force ? '?force=true' : ''
    response = await fetch(`${DOCUMENT_ENDPOINT}/${encodeURIComponent(documentId)}/index${query}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` }
    })
  } catch {
    throw new ApiError('无法连接到索引服务，请稍后重试')
  }
  return readApiResponse(response, '文档索引构建失败')
}

export async function searchKnowledge(
  token: string,
  query: string,
  limit: number
): Promise<KnowledgeSearchResponse> {
  let response: Response
  try {
    response = await fetch('/api/agent/admin/knowledge/search', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ query, limit })
    })
  } catch {
    throw new ApiError('无法连接到向量检索服务，请稍后重试')
  }
  return readApiResponse(response, '向量检索失败')
}
