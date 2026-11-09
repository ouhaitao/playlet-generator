import type { Project, Shot, ShotVersion, PromptDraft, CharacterRef, Scene } from '@/types/domain'

/** 后端 REST 客户端。开发期经 Vite proxy 转发到 :8080。 */
const BASE = '/api'

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!res.ok) {
    throw new Error(`${res.status} ${res.statusText} @ ${path}`)
  }
  return (await res.json()) as T
}

export const api = {
  projects: {
    list: () => json<Project[]>('/projects'),
    get: (id: number) => json<Project>(`/projects/${id}`),
    create: (p: Partial<Project>) =>
      json<Project>('/projects', { method: 'POST', body: JSON.stringify(p) }),
  },
  shots: {
    list: (projectId: number) => json<Shot[]>(`/projects/${projectId}/shots`),
    update: (projectId: number, shotId: number, s: Partial<Shot>) =>
      json<Shot>(`/projects/${projectId}/shots/${shotId}`, {
        method: 'PATCH',
        body: JSON.stringify(s),
      }),
  },
  versions: {
    list: (shotId: number) => json<ShotVersion[]>(`/shots/${shotId}/versions`),
  },
  drafts: {
    list: (ownerType: string, ownerId: number) =>
      json<PromptDraft[]>(`/drafts?ownerType=${ownerType}&ownerId=${ownerId}`),
    confirm: (draftId: number) =>
      json<PromptDraft>(`/drafts/${draftId}/confirm`, { method: 'POST' }),
    reject: (draftId: number, feedback: string) =>
      json<PromptDraft>(`/drafts/${draftId}/reject`, {
        method: 'POST',
        body: JSON.stringify({ feedback }),
      }),
  },
  characters: {
    list: (projectId: number) => json<CharacterRef[]>(`/projects/${projectId}/characters`),
  },
  scenes: {
    list: (projectId: number) => json<Scene[]>(`/projects/${projectId}/scenes`),
  },
}
