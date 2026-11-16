import type { Project, Shot, ShotVersion, PromptDraft, CharacterRef, Scene,
  StoryboardRow, StoryboardGenerateResult, StoryboardConfirmResult, ComposeResult } from '@/types/domain'

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
    /** 一键合成成片（异步）：校验通过即返回 COMPOSING，进度走 WS compose_* 事件。 */
    compose: (id: number) =>
      json<ComposeResult>(`/projects/${id}/compose`, { method: 'POST' }),
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
    /** 提交图片生成（异步）。params 为 jsonPath → 值，按工作流解析元数据填写。 */
    image: (shotId: number, workflow: string, params: Record<string, unknown>) =>
      json<ShotVersion>(`/shots/${shotId}/generate-image`, {
        method: 'POST',
        body: JSON.stringify({ workflow, params }),
      }),
    /** 提交视频生成（异步）。图生视频的图片地址由用户填进 params。 */
    video: (shotId: number, workflow: string, params: Record<string, unknown>) =>
      json<ShotVersion>(`/shots/${shotId}/generate-video`, {
        method: 'POST',
        body: JSON.stringify({ workflow, params }),
      }),
  },
  workflows: {
    list: (type?: WorkflowType) =>
      json<WorkflowEntry[]>(type ? `/workflows?type=${type}` : '/workflows'),
    get: (name: string) => json<WorkflowDetail>(`/workflows/${name}`),
    /** 导入：content 为 API 格式 JSON 原文（字符串），type 为 IMAGE/VIDEO，model 为模型名（必填）。
     *  ok=false 时 problems 有具体原因。 */
    import: (name: string, type: WorkflowType, model: string, content: string) =>
      json<WorkflowImportReport>(
        `/workflows?name=${encodeURIComponent(name)}&type=${type}&model=${encodeURIComponent(model)}`,
        { method: 'POST', body: content },
      ),
    remove: (name: string) =>
      json<void>(`/workflows/${name}`, { method: 'DELETE' }),
  },
}

/** 工作流类型枚举（图片 / 视频）。 */
export type WorkflowType = 'IMAGE' | 'VIDEO'

/** 工作流一个节点下可人工填写的字面量参数。 */
export interface WorkflowFieldParam {
  inputName: string
  /** STRING / NUMBER / BOOLEAN */
  kind: string
  /** 写回路径，如 "3/inputs/seed"；提交生成时作为 params 的键 */
  jsonPath: string
  /** 导入时原始工作流中的默认值 */
  defaultValue: string | number | boolean | null
}

/** 按节点分组的参数集合。 */
export interface WorkflowNodeGroup {
  nodeId: string
  classType: string
  nodeTitle: string
  fields: WorkflowFieldParam[]
}

export interface WorkflowEntry {
  name: string
  source: 'builtin' | 'user'
  /** 无解析元数据（旧内置模板）时为 null，不可用于生成 */
  type: WorkflowType | null
  /** 导入时用户填写的模型名（提示词优化 agent 做模型级优化用） */
  model: string | null
  importedAt: string | null
  bytes: number
  overridden: boolean
}

export interface WorkflowDetail {
  name: string
  source: 'builtin' | 'user'
  type: WorkflowType | null
  model: string | null
  importedAt: string | null
  groups: WorkflowNodeGroup[]
}

export interface WorkflowImportReport {
  name: string
  ok: boolean
  problems: string[]
  type: WorkflowType | null
  model: string | null
  nodeCount: number
  fieldCount: number
}
