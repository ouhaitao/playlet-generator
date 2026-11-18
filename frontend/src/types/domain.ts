/** 与后端领域模型对齐的前端类型（DESIGN.md §5/§6.1）。 */

export type ProjectMode = 'STORYBOARD' | 'ONE_SHOT'
export type ShotState =
  | 'PENDING'
  | 'FIRST_FRAME_GENERATING'
  | 'FIRST_FRAME_REVIEW'
  | 'VIDEO_GENERATING'
  | 'VIDEO_REVIEW'
  | 'TTS_GENERATING'
  | 'TTS_REVIEW'
  | 'APPROVED'
  | 'ROLLED_BACK'
export type FirstFrameSource = 'REFERENCE' | 'PREV_LAST_FRAME'
export type VersionKind = 'FIRST_FRAME' | 'VIDEO' | 'TTS'
export type ReviewDecision = 'APPROVE' | 'REGENERATE' | 'ROLLBACK'
export type DraftState = 'DRAFT' | 'CONFIRMED' | 'REJECTED'

export interface Project {
  id: number
  synopsis: string
  mode: ProjectMode
  aspectRatio: string
  videoModel: string
  targetDurationSec?: number
  style?: string
  resolutionW?: number
  resolutionH?: number
  audioNative: boolean
  audioDialogue: boolean
  audioBgm: boolean
  bgmAssetPath?: string
  upscaleEnabled: boolean
  state: string
  /** 成片相对存储根路径（/media/store/ 前缀可播）；合成成功后有值 */
  finalVideoPath?: string
  composedAt?: string
  /** 最近一次合成失败原因 */
  composeError?: string
}

/** POST /api/projects/{id}/compose 立即返回（异步，进度走 WS compose_* 事件）。 */
export interface ComposeResult {
  state: 'COMPOSING'
  finalVideoPath?: string
  message?: string
}

export interface CharacterRef {
  id: number
  name: string
  appearance: string
  referenceImage?: string
  refSource?: 'LIBRARY' | 'UPLOAD' | 'GENERATED'
  voiceKey?: string
}

export interface Scene {
  id: number
  name: string
  description: string
  referenceImage?: string
  refSource?: 'LIBRARY' | 'UPLOAD' | 'GENERATED'
}

export interface Shot {
  id: number
  ord: number
  visualPrompt: string
  /** 已确认的负向提示（§6.3 确认门写入槽位） */
  negativePrompt?: string | null
  durationSec: number
  firstFrameSource: FirstFrameSource
  videoModel?: string
  sceneId?: number
  dialogue?: string
  speakerId?: number
  shotSize: string
  cameraMove: string
  sfxNote?: string
  firstFrameImage?: string
  state: ShotState
  characterIds: number[]
}

export interface ShotVersion {
  id: number
  versionNo: number
  kind: VersionKind
  backend: string
  prompt?: string
  seed?: number
  artifactPath?: string
  durationSec?: number
  reviewState: 'PENDING' | 'APPROVED' | 'REJECTED'
  rejectReason?: string
  regenLevel?: number
  comfyPromptId?: string
  /** 生成参数留档：`{workflow, params}` 的 JSON 字符串（审核台「显示工作流」用） */
  paramsJson?: string | null
  genState: 'GENERATING' | 'DONE' | 'FAILED'
  errorMessage?: string
}

export interface PromptDraft {
  id: number
  ownerType: 'SHOT' | 'CHARACTER' | 'SCENE'
  ownerId: number
  fieldName: string
  baseContent?: string
  content: string
  negativePrompt?: string
  versionNo: number
  state: DraftState
  feedback?: string
  confirmedAt?: string
}

/** 闸门①分镜表一行（后端 StoryboardService.Row）：镜头字段 + 最新草稿状态。 */
export interface StoryboardRow {
  shotId: number
  ord: number
  durationSec: number
  shotSize: string
  cameraMove: string
  dialogue?: string | null
  speaker?: string | null
  scene?: string | null
  firstFrameSource?: FirstFrameSource | null
  shotState?: ShotState | null
  visualPrompt?: string | null
  negativePrompt?: string | null
  draftId?: number | null
  draftVersion: number
  draftState?: DraftState | null
  draftContent?: string | null
  draftNegative?: string | null
  confirmed: boolean
}

/** 切镜结果：整表 + 是否走 LLM（false = 回退切句，建议逐镜润色）。 */
export interface StoryboardGenerateResult {
  rows: StoryboardRow[]
  usedLlm: boolean
  shotCount: number
}

/** 批量确认结果。 */
export interface StoryboardConfirmResult {
  confirmedNow: number
  stillPending: number
  projectState: string
}

/** 手动添加分镜的入参（全部用户填写；仅 visualPrompt 必填）。 */
export interface StoryboardAddShotSpec {
  /** 目标序号；缺省/越界追加表尾，占用已有序号时后续镜头顺延后移 */
  ord?: number
  /** 时长（秒）；缺省 5 */
  durationSec?: number
  /** 景别；缺省「中景」 */
  shotSize?: string
  /** 运镜；缺省「固定」 */
  cameraMove?: string
  /** 画面提示词（必填，直接写槽、不产草稿） */
  visualPrompt?: string
  /** 负向提示词（可空） */
  negativePrompt?: string
  /** 台词（可空） */
  dialogue?: string
  /** 说话角色名（可空，按名字挂接既有引用） */
  speaker?: string
  /** 场景名（可空，按名字挂接既有引用） */
  scene?: string
}
