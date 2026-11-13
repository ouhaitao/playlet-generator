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
