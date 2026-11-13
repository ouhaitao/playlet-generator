import type { Project, Shot, ShotVersion, PromptDraft, CharacterRef, Scene,
  StoryboardRow, StoryboardGenerateResult, StoryboardConfirmResult } from '@/types/domain'

/** 后端 REST 客户端。开发期经 Vite proxy 转发到 :8080。 */
const BASE = '/api'

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!res.ok) {
    // 后端业务错误带 { message }（GlobalExceptionHandler），优先展示中文原因
    let detail = ''
    try {
      const body = (await res.json()) as { message?: string }
      detail = body.message ? `：${body.message}` : ''
    } catch {
      /* 非 JSON 响应体，忽略 */
    }
    throw new Error(`${res.status}${detail || ` ${res.statusText}`} @ ${path}`)
  }
  // DELETE 等 204 无响应体
  if (res.status === 204) {
    return undefined as T
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
    get: (shotId: number) => json<Shot>(`/shots/${shotId}`),
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
    /** 触发 agent 优化，产出新草稿（旧未决草稿作废）。 */
    optimize: (ownerType: string, ownerId: number, fieldName: string, feedback?: string) =>
      json<PromptDraft>(`/drafts/optimize`, {
        method: 'POST',
        body: JSON.stringify({ ownerType, ownerId, fieldName, feedback }),
      }),
    /** 用户手改文本直接成新草稿（闸门①逐行改，不擅自重优化）。 */
    manual: (shotId: number, content: string) =>
      json<PromptDraft>(`/drafts/manual`, {
        method: 'POST',
        body: JSON.stringify({ shotId, content }),
      }),
    confirm: (draftId: number) =>
      json<PromptDraft>(`/drafts/${draftId}/confirm`, { method: 'POST' }),
    reject: (draftId: number, feedback: string) =>
      json<PromptDraft>(`/drafts/${draftId}/reject`, {
        method: 'POST',
        body: JSON.stringify({ feedback }),
      }),
  },
  storyboard: {
    /** 梗概 → 切镜 → 逐镜出草稿 → 整表返回。 */
    generate: (projectId: number) =>
      json<StoryboardGenerateResult>(`/projects/${projectId}/storyboard/generate`, {
        method: 'POST',
      }),
    /** 分镜表整表（序/时长/景别/运镜/草稿状态）。 */
    table: (projectId: number) =>
      json<StoryboardRow[]>(`/projects/${projectId}/storyboard/table`),
    /** 闸门①批量确认：逐镜确认最新草稿；全确认后项目状态推进。 */
    confirmAll: (projectId: number) =>
      json<StoryboardConfirmResult>(`/projects/${projectId}/storyboard/confirm-all`, {
        method: 'POST',
      }),
    /** 逐行改提示词：修改结果成为新草稿（再确认才写槽）。 */
    editPrompt: (projectId: number, shotId: number, content: string) =>
      json<StoryboardRow>(`/projects/${projectId}/storyboard/shots/${shotId}/prompt`, {
        method: 'POST',
        body: JSON.stringify({ content }),
      }),
  },
  characters: {
    list: (projectId: number) => json<CharacterRef[]>(`/projects/${projectId}/characters`),
  },
  scenes: {
    list: (projectId: number) => json<Scene[]>(`/projects/${projectId}/scenes`),
  },
  review: {
    approve: (versionId: number) =>
      json<ShotVersion>(`/versions/${versionId}/approve`, { method: 'POST' }),
    reject: (versionId: number, feedback: string, regenLevel?: number) =>
      json<ShotVersion>(`/versions/${versionId}/reject`, {
        method: 'POST',
        body: JSON.stringify({ feedback, regenLevel }),
      }),
  },
  generate: {
    /** 提交首帧图生成，立即返回 GENERATING 版本（异步）。 */
    firstFrame: (shotId: number, opts?: { seed?: number; backend?: string }) =>
      json<ShotVersion>(`/shots/${shotId}/generate-first-frame`, {
        method: 'POST',
        body: JSON.stringify(opts ?? {}),
      }),
    /** 提交视频生成，立即返回 GENERATING 版本（异步）。 */
    video: (shotId: number, seed?: number) =>
      json<ShotVersion>(`/shots/${shotId}/generate-video`, {
        method: 'POST',
        body: JSON.stringify(seed != null ? { seed } : {}),
      }),
  },
  workflows: {
    list: () => json<WorkflowEntry[]>('/workflows'),
    get: (name: string) => json<WorkflowDetail>(`/workflows/${name}`),
    /** 导入：content 为 API 格式 JSON 原文（字符串）。ok=false 时 problems 有具体原因。 */
    import: (name: string, content: string) =>
      json<WorkflowImportReport>(`/workflows?name=${encodeURIComponent(name)}`, {
        method: 'POST',
        body: content,
      }),
    remove: (name: string) =>
      json<void>(`/workflows/${name}`, { method: 'DELETE' }),
  },
}

/** 工作流模板库条目。 */
export interface WorkflowSlotBrief {
  name: string
  type: string
  sourceKind: 'source' | 'chain'
  hostId: string
}

export interface WorkflowEntry {
  name: string
  source: 'builtin' | 'user'
  slots: WorkflowSlotBrief[]
  importedAt: string | null
  bytes: number
  overridden: boolean
}

export interface WorkflowDetail {
  name: string
  source: 'builtin' | 'user'
  slots: WorkflowSlotBrief[]
  workflow: Record<string, unknown>
}

export interface WorkflowImportReport {
  name: string
  ok: boolean
  problems: string[]
  slots: WorkflowSlotBrief[]
  substitutions: string[]
  notes: string[]
}
