import { useEffect, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, X } from 'lucide-react'
import { api } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input, Textarea } from '@/components/ui/input'
import { ErrorState } from '@/components/ui/states'
import { cn } from '@/lib/utils'
import type { ProjectMode } from '@/types/domain'

/** 画幅选项：与后端 MiniMaxH3Backend.toTemplateAspectRatio 支持的枚举对齐。 */
const ASPECT_RATIOS = ['9:16', '16:9', '1:1', '2:3', '3:2', '3:4', '4:3', '21:9']

const MODES: { value: ProjectMode; label: string; hint: string }[] = [
  { value: 'STORYBOARD', label: '分镜', hint: '每镜首帧独立生成，镜头间跳切 + 转场' },
  { value: 'ONE_SHOT', label: '一镜到底', hint: '续帧链，零转场硬接' },
]

const SELECT_CLS =
  'h-8 w-full rounded-md border border-input bg-card px-2 text-sm ' +
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50'

/**
 * 创建项目弹窗：必填槽位 = synopsis + mode + aspectRatio（后端 Project 实体约定），
 * 其余字段可空走默认（videoModel 当前仅 MINIMAX_H3 一个后端，固定发送）。
 * 成功后回调 onCreated(新项目 id)，由列表页直接切入详情。
 */
export default function ProjectCreateDialog({
  onCreated,
  onClose,
}: {
  onCreated: (projectId: number) => void
  onClose: () => void
}) {
  const qc = useQueryClient()
  const [synopsis, setSynopsis] = useState('')
  const [mode, setMode] = useState<ProjectMode>('STORYBOARD')
  const [aspectRatio, setAspectRatio] = useState('9:16')
  const [targetDurationSec, setTargetDurationSec] = useState('')
  const [style, setStyle] = useState('')
  const [audioNative, setAudioNative] = useState(true)
  const [audioDialogue, setAudioDialogue] = useState(true)
  const [audioBgm, setAudioBgm] = useState(true)
  const [upscaleEnabled, setUpscaleEnabled] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  // Esc 关闭
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const createMutation = useMutation({
    mutationFn: () =>
      api.projects.create({
        synopsis: synopsis.trim(),
        mode,
        aspectRatio,
        videoModel: 'MINIMAX_H3',
        ...(targetDurationSec.trim() ? { targetDurationSec: Number(targetDurationSec) } : {}),
        ...(style.trim() ? { style: style.trim() } : {}),
        audioNative,
        audioDialogue,
        audioBgm,
        upscaleEnabled,
      }),
    onSuccess: (p) => {
      qc.invalidateQueries({ queryKey: ['projects'] })
      onCreated(p.id)
    },
    onError: (e) => setFormError(String((e as Error).message || e)),
  })

  const canSubmit = synopsis.trim().length > 0 && !createMutation.isPending

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center bg-black/40 p-4 pt-[8vh]"
      onClick={onClose}
    >
      <Card
        className="max-h-[84vh] w-full max-w-lg overflow-y-auto shadow-pop"
        onClick={(e) => e.stopPropagation()}
      >
        <CardHeader>
          <CardTitle>创建项目</CardTitle>
          <Button variant="ghost" size="xs" title="关闭" onClick={onClose}>
            <X size={13} />
          </Button>
        </CardHeader>
        <CardContent className="space-y-3">
          <label className="block space-y-1">
            <div className="text-2xs text-muted-foreground">
              剧情梗概 <span className="text-destructive">*</span>
            </div>
            <Textarea
              rows={4}
              placeholder="用自然语言描述剧情，后续「① 分镜表」会据此自动切镜…"
              value={synopsis}
              onChange={(e) => setSynopsis(e.target.value)}
            />
          </label>

          <div className="space-y-1">
            <div className="text-2xs text-muted-foreground">模式</div>
            <div className="flex gap-1">
              {MODES.map((m) => (
                <Button
                  key={m.value}
                  variant={mode === m.value ? 'primary' : 'outline'}
                  size="sm"
                  className="flex-1"
                  title={m.hint}
                  onClick={() => setMode(m.value)}
                >
                  {m.label}
                </Button>
              ))}
            </div>
          </div>

          <div className="grid grid-cols-2 gap-2">
            <label className="block space-y-1">
              <div className="text-2xs text-muted-foreground">画幅</div>
              <select
                className={SELECT_CLS}
                value={aspectRatio}
                onChange={(e) => setAspectRatio(e.target.value)}
              >
                {ASPECT_RATIOS.map((r) => (
                  <option key={r} value={r}>
                    {r}
                  </option>
                ))}
              </select>
            </label>
            <label className="block space-y-1">
              <div className="text-2xs text-muted-foreground">目标时长（秒，可选）</div>
              <Input
                type="number"
                min={1}
                placeholder="如 60"
                value={targetDurationSec}
                onChange={(e) => setTargetDurationSec(e.target.value)}
              />
            </label>
          </div>

          <label className="block space-y-1">
            <div className="text-2xs text-muted-foreground">风格（可选）</div>
            <Input
              placeholder="如 电影感写实 / 日系动漫"
              value={style}
              onChange={(e) => setStyle(e.target.value)}
            />
          </label>

          <div className="space-y-1">
            <div className="text-2xs text-muted-foreground">生成选项</div>
            <div className="flex flex-wrap gap-x-4 gap-y-1.5 pt-0.5">
              <Toggle checked={audioNative} onChange={setAudioNative} label="原生音频" />
              <Toggle checked={audioDialogue} onChange={setAudioDialogue} label="台词层" />
              <Toggle checked={audioBgm} onChange={setAudioBgm} label="BGM 层" />
              <Toggle checked={upscaleEnabled} onChange={setUpscaleEnabled} label="超分" />
            </div>
          </div>

          {formError && <ErrorState message={formError} />}

          <div className="flex justify-end gap-2 pt-1">
            <Button variant="outline" size="sm" onClick={onClose}>
              取消
            </Button>
            <Button
              variant="primary"
              size="sm"
              disabled={!canSubmit}
              onClick={() => {
                setFormError(null)
                createMutation.mutate()
              }}
            >
              <Plus size={13} /> {createMutation.isPending ? '创建中…' : '创建'}
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}

/** 表单内小开关：原生 checkbox + accent 主色，保持紧凑密度。 */
function Toggle({
  checked,
  onChange,
  label,
}: {
  checked: boolean
  onChange: (v: boolean) => void
  label: string
}) {
  return (
    <label className={cn('flex cursor-pointer items-center gap-1.5 text-xs select-none')}>
      <input
        type="checkbox"
        className="accent-primary"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
      />
      {label}
    </label>
  )
}
