import { useEffect, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { api } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input, Textarea } from '@/components/ui/input'
import { ErrorState } from '@/components/ui/states'
import type { StoryboardAddShotSpec } from '@/types/domain'

/**
 * 手动添加分镜弹窗（闸门①）：参数全部由用户填写，不经 LLM 切镜/优化。
 * 提交 → POST /api/projects/{id}/storyboard/shots，后端把提示词直接写槽位（不产草稿）。
 * ord 缺省追加表尾；填了已占用的序号，后端会把后续镜头顺延后移。
 */
export default function ShotAddDialog({
  projectId,
  onAdded,
  onClose,
}: {
  projectId: number
  onAdded: () => void
  onClose: () => void
}) {
  const qc = useQueryClient()
  const [ord, setOrd] = useState('')
  const [durationSec, setDurationSec] = useState('')
  const [shotSize, setShotSize] = useState('')
  const [cameraMove, setCameraMove] = useState('')
  const [visualPrompt, setVisualPrompt] = useState('')
  const [negativePrompt, setNegativePrompt] = useState('')
  const [dialogue, setDialogue] = useState('')
  const [speaker, setSpeaker] = useState('')
  const [scene, setScene] = useState('')
  const [formError, setFormError] = useState<string | null>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const add = useMutation({
    mutationFn: () => {
      const spec: StoryboardAddShotSpec = { visualPrompt: visualPrompt.trim() }
      if (ord.trim()) spec.ord = Number(ord)
      if (durationSec.trim()) spec.durationSec = Number(durationSec)
      if (shotSize.trim()) spec.shotSize = shotSize.trim()
      if (cameraMove.trim()) spec.cameraMove = cameraMove.trim()
      if (negativePrompt.trim()) spec.negativePrompt = negativePrompt.trim()
      if (dialogue.trim()) spec.dialogue = dialogue.trim()
      if (speaker.trim()) spec.speaker = speaker.trim()
      if (scene.trim()) spec.scene = scene.trim()
      return api.storyboard.addShot(projectId, spec)
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['storyboard', projectId] })
      qc.invalidateQueries({ queryKey: ['project', projectId] })
      qc.invalidateQueries({ queryKey: ['shots', projectId] })
      onAdded()
    },
    onError: (e) => setFormError(String((e as Error).message || e)),
  })

  const canSubmit = visualPrompt.trim().length > 0 && !add.isPending

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center bg-black/40 p-4 pt-[6vh]"
      onClick={onClose}
    >
      <Card
        className="max-h-[88vh] w-full max-w-lg overflow-y-auto shadow-pop"
        onClick={(e) => e.stopPropagation()}
      >
        <CardHeader>
          <CardTitle>手动添加分镜</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <label className="block space-y-1">
            <span className="text-2xs text-muted-foreground">
              画面提示词 <span className="text-destructive">*</span>
            </span>
            <Textarea
              rows={3}
              className="text-xs"
              placeholder="该镜的画面描述（中文，直接写入槽位）"
              value={visualPrompt}
              onChange={(e) => setVisualPrompt(e.target.value)}
            />
          </label>

          <label className="block space-y-1">
            <span className="text-2xs text-muted-foreground">负向提示词</span>
            <Textarea
              rows={2}
              className="text-xs"
              placeholder="可空"
              value={negativePrompt}
              onChange={(e) => setNegativePrompt(e.target.value)}
            />
          </label>

          <div className="grid grid-cols-2 gap-2">
            <label className="block space-y-1">
              <span className="text-2xs text-muted-foreground">序号（缺省表尾）</span>
              <Input
                type="number"
                className="h-7 text-xs"
                placeholder="追加到末尾"
                value={ord}
                onChange={(e) => setOrd(e.target.value)}
              />
            </label>
            <label className="block space-y-1">
              <span className="text-2xs text-muted-foreground">时长（秒，缺省 5）</span>
              <Input
                type="number"
                step="any"
                className="h-7 text-xs"
                placeholder="5"
                value={durationSec}
                onChange={(e) => setDurationSec(e.target.value)}
              />
            </label>
            <label className="block space-y-1">
              <span className="text-2xs text-muted-foreground">景别（缺省 中景）</span>
              <Input
                className="h-7 text-xs"
                placeholder="中景"
                value={shotSize}
                onChange={(e) => setShotSize(e.target.value)}
              />
            </label>
            <label className="block space-y-1">
              <span className="text-2xs text-muted-foreground">运镜（缺省 固定）</span>
              <Input
                className="h-7 text-xs"
                placeholder="固定"
                value={cameraMove}
                onChange={(e) => setCameraMove(e.target.value)}
              />
            </label>
            <label className="block space-y-1">
              <span className="text-2xs text-muted-foreground">说话角色名</span>
              <Input
                className="h-7 text-xs"
                placeholder="按名字挂接既有角色"
                value={speaker}
                onChange={(e) => setSpeaker(e.target.value)}
              />
            </label>
            <label className="block space-y-1">
              <span className="text-2xs text-muted-foreground">场景名</span>
              <Input
                className="h-7 text-xs"
                placeholder="按名字挂接既有场景"
                value={scene}
                onChange={(e) => setScene(e.target.value)}
              />
            </label>
          </div>

          <label className="block space-y-1">
            <span className="text-2xs text-muted-foreground">台词（可空）</span>
            <Textarea
              rows={2}
              className="text-xs"
              placeholder="纯画面镜头可留空"
              value={dialogue}
              onChange={(e) => setDialogue(e.target.value)}
            />
          </label>

          {formError && <ErrorState message={formError} />}

          <div className="flex justify-end gap-2 pt-1">
            <Button variant="ghost" size="sm" onClick={onClose}>
              取消
            </Button>
            <Button variant="primary" size="sm" disabled={!canSubmit} onClick={() => add.mutate()}>
              <Plus size={13} /> {add.isPending ? '添加中…' : '添加'}
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
