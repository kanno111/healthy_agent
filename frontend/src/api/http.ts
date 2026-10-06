export type ApiResponse<T> = {
  code: number
  message: string
  data: T
}

export class ApiError extends Error {
  readonly status?: number
  readonly code?: number

  constructor(message: string, options?: { status?: number; code?: number }) {
    super(message)
    this.name = 'ApiError'
    this.status = options?.status
    this.code = options?.code
  }
}

export async function readApiResponse<T>(response: Response, fallbackMessage: string): Promise<T> {
  const body = await response.json().catch(() => null) as ApiResponse<T> | null
  if (!response.ok || !body || body.code !== 0) {
    throw new ApiError(body?.message || fallbackMessage, {
      status: response.status,
      code: body?.code
    })
  }
  return body.data
}

