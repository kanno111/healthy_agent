import { session } from '../stores/session'

/**
 * Local-only trusted identity headers.
 *
 * The Agent backend currently does not validate the Healthy JWT. It trusts the
 * user id and role returned by Healthy's login response and stored in the
 * current browser tab. Never use these client-controlled headers in production.
 */
export function agentRequestHeaders(token: string): Record<string, string> {
  const headers: Record<string, string> = {
    Authorization: `Bearer ${token}`
  }
  if (session.userId && session.role) {
    headers['X-Auth-User-Id'] = String(session.userId)
    headers['X-Auth-Role'] = session.role
  }
  return headers
}
