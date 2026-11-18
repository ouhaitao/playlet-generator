import { useEffect, useRef, useState } from 'react'
import { api } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/input'
import { ErrorState } from '@/components/ui/states'
import type { Shot, ShotVersion } from '@/types/domain'

const SELECT_CLS =
  'h-8 w-full rounded-md border border-input bg-card px-2 text-sm ' +
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50'

/** 已有内容时用 `,` 连接追加；无内容直接用新值。 */
function appendWithComma(existing: string, addition: string): string {
  const a = (existing ?? '').trim()
  const b = (addition ?? '').trim()
  if (!b) return existing ?? ''
  if (!a) return b
  return `${a},${b}`
}

/** 生成提示词弹框的「标识」选项：正面 / 负面。 */
const FLAG_OPTIONS = [
  { value: '正面', label: '正面提示词' },
  { value: '负面', label: '负面提示词' },
]

/**
 * 审核台 CLIPTextEncode 输入框下方的提示词工具条（5 个按钮）：
 * ① 填入分镜正向提示词 ② 填入分镜负向提示词 ③ 生成提示词（弹框）
 * ④ 提示词翻译成英文 ⑤ 提示词翻译成中文。
 *
 * <p>翻译结果按「字段内容 + 目标语言」缓存：内容未变更时再次点击直接展示上次结果、
 * 不调接口；一旦输入框内容发生任何变更（含编辑后又还原，以变更事件计）即失效，
 * 下次点击重新调用。翻译/生成结果<b>仅前端展示，不落库</b>，由用户决定是否「填入输入框」。
 */
export default function PromptFieldTools({
  value,
  onChange,
  shot,
  model,
  versions,
}: {
  /** 当前 CLIPTextEncode 字段内容 */
  value: string
  /** 写字段内容（填入 / 追加用） */
  onChange: (v: string) => void
  /** 所属镜头（取正/负向提示词槽位） */
  shot?: Shot
  /** 工作流元信息的 model（生成提示词用） */
  model?: string | null
  /** 已生成版本列表（取「最后一张被拒」的审核意见作生成弹框默认反馈） */
  versions?: ShotVersion[]
}) {
  // ── 翻译缓存 ────────────────────────────────────────────
  // dirtyRef：内容自上次翻译后是否变更过（含改了又还原 → 仍算变更）
  const dirtyRef = useRef(false)
  const mountedRef = useRef(false)
  const cacheRef = useRef<{ text: string; en?: string; zh?: string }>({ text: '' })
  const [shown, setShown] = useState<{ target: 'EN' | 'ZH'; text: string } | null>(null)
  const [busy, setBusy] = useState<'EN' | 'ZH' | null>(null)
  const [err, setErr] = useState<string | null>(null)

  // 内容任何变更（父组件 onChange 回流）都置脏；跳过首次挂载
  useEffect(() => {
    if (!mountedRef.current) {
      mountedRef.current = true
      return
    }
    dirtyRef.current = true
  }, [value])

  function translate(target: 'EN' | 'ZH') {
    if (busy) return
    const cur = value ?? ''
    const key = target.toLowerCase() as 'en' | 'zh'
    // 未变更且已有该目标语言的缓存 → 直接展示，不调接口
    if (!dirtyRef.current && cacheRef.current.text === cur && cacheRef.current[key]) {
      setShown({ target, text: cacheRef.current[key] as string })
      setErr(null)
      return
    }
    setBusy(target)
    setErr(null)
    api.drafts
      .translate(cur, target)
      .then((r) => {
        cacheRef.current = { text: cur, en: cacheRef.current.en, zh: cacheRef.current.zh }
        cacheRef.current[key] = r.translated
        dirtyRef.current = false
        setShown({ target, text: r.translated })
      })
      .catch((e) => setErr(String((e as Error).message || e)))
      .finally(() => setBusy(null))
  }

  // ── 生成提示词弹框 ──────────────────────────────────────
  const [genOpen, setGenOpen] = useState(false)

  function fillFromShot(kind: 'positive' | 'negative') {
    const src = kind === 'positive' ? shot?.visualPrompt : shot?.negativePrompt
    if (!src || !src.trim()) return
    onChange(appendWithComma(value ?? '', src))
  }

  return (
    <div className="space-y-1.5">
      <div className="flex flex-wrap gap-1.5">
        <Button variant="outline" size="xs" onClick={() => fillFromShot('positive')}>
          填入分镜正向提示词
        </Button>
        <Button variant="outline" size="xs" onClick={() => fillFromShot('negative')}>
          填入分镜负向提示词
        </Button>
        <Button variant="outline" size="xs" onClick={() => setGenOpen(true)}>
          生成提示词
        </Button>
        <Button
          variant="outline"
          size="xs"
          disabled={busy === 'EN'}
          onClick={() => translate('EN')}
        >
          {busy === 'EN' ? '翻译中…' : '提示词翻译成英文'}
        </Button>
        <Button
          variant="outline"
          size="xs"
          disabled={busy === 'ZH'}
          onClick={() => translate('ZH')}
        >
          {busy === 'ZH' ? '翻译中…' : '提示词翻译成中文'}
        </Button>
      </div>

      {err && <ErrorState message={err} />}

      {shown && (
        <div className="rounded-md border bg-muted/30 p-2">
          <div className="flex items-center justify-between gap-2">
            <span className="text-2xs text-muted-foreground">
              翻译结果（{shown.target === 'EN' ? '英文' : '中文'}，仅展示不落库）
            </span>
            <div className="flex gap-1.5">
              <Button
                variant="ghost"
                size="xs"
                onClick={() => {
                  onChange(shown.text)
                  setShown(null)
                }}
              >
                填入输入框
              </Button>
              <Button variant="ghost" size="xs" onClick={() => setShown(null)}>
                关闭
              </Button>
            </div>
          </div>
          <p className="mt-1 whitespace-pre-wrap break-words text-xs leading-relaxed">
            {shown.text}
          </p>
        </div>
      )}

      {genOpen && (
        <GeneratePromptDialog
          model={model}
          content={value ?? ''}
          versions={versions}
          onClose={() => setGenOpen(false)}
          onFill={(text) => {
            onChange(text)
            setGenOpen(false)
          }}
        />
      )}
    </div>
  )
}

/**
 * 「生成提示词」弹框：填「标识（正面/负面）」+「反馈意见」→ 调 /api/prompt/generate。
 * 反馈意见默认值：已生成列表中最后一张若为 REJECTED，取其审核意见；否则空。
 * 结果仅展示，用户点「填入输入框」才写回字段。
 */
function GeneratePromptDialog({
  model,
  content,
  versions,
  onClose,
  onFill,
}: {
  model?: string | null
  content: string
  versions?: ShotVersion[]
  onClose: () => void
  onFill: (text: string) => void
}) {
  const last = versions && versions.length > 0 ? versions[versions.length - 1] : undefined
  const defaultFeedback =
    last && last.reviewState === 'REJECTED' && last.rejectReason ? last.rejectReason : ''

  const [flag, setFlag] = useState(FLAG_OPTIONS[0].value)
  const [feedback, setFeedback] = useState(defaultFeedback)
  const [result, setResult] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  function run() {
    if (busy) return
    setBusy(true)
    setErr(null)
    api.prompt
      .generate({ model, content, flag, feedback: feedback.trim() || undefined })
      .then((r) => setResult(r.result))
      .catch((e) => setErr(String((e as Error).message || e)))
      .finally(() => setBusy(false))
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center bg-black/40 p-4 pt-[8vh]"
      onClick={onClose}
    >
      <div
        className="max-h-[84vh] w-full max-w-lg overflow-y-auto rounded-lg border bg-card p-4 shadow-pop"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-3 flex items-center justify-between">
          <h3 className="text-sm font-semibold">生成提示词</h3>
          <Button variant="ghost" size="xs" onClick={onClose}>
            关闭
          </Button>
        </div>

        <div className="space-y-3">
          <label className="block space-y-1">
            <span className="text-2xs text-muted-foreground">标识</span>
            <select
              className={SELECT_CLS}
              value={flag}
              onChange={(e) => setFlag(e.target.value)}
            >
              {FLAG_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </label>

          <label className="block space-y-1">
            <span className="text-2xs text-muted-foreground">反馈意见</span>
            <Textarea
              rows={3}
              className="text-xs"
              placeholder="对当前提示词的修改意见（可空）"
              value={feedback}
              onChange={(e) => setFeedback(e.target.value)}
            />
          </label>

          <div className="text-2xs text-muted-foreground">
            目标模型：<span className="font-mono">{model || '（未指定）'}</span>
          </div>

          <Button variant="primary" size="sm" disabled={busy} onClick={run}>
            {busy ? '生成中…' : '生成'}
          </Button>

          {err && <ErrorState message={err} />}

          {result && (
            <div className="rounded-md border bg-muted/30 p-2">
              <div className="flex items-center justify-between gap-2">
                <span className="text-2xs text-muted-foreground">生成结果（仅展示不落库）</span>
                <Button variant="ghost" size="xs" onClick={() => onFill(result)}>
                  填入输入框
                </Button>
              </div>
              <p className="mt-1 whitespace-pre-wrap break-words text-xs leading-relaxed">
                {result}
              </p>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
