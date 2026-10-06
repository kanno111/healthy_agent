import { ApiError, readApiResponse } from './http'

export type PatientLoginResult = {
  token: string
  userId: number
  name: string
  role: string
}

export async function login(username: string, password: string): Promise<PatientLoginResult> {
  let response: Response
  try {
    response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    })
  } catch {
    throw new ApiError('无法连接到登录服务，请确认 Gateway 和 Identity 已启动')
  }

  return readApiResponse(response, '登录失败，请稍后重试')
}

export async function logout(token: string): Promise<void> {
  try {
    const response = await fetch('/api/auth/logout', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` }
    })
    await readApiResponse<null>(response, '退出登录失败')
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError('无法连接到退出登录服务')
  }
}

