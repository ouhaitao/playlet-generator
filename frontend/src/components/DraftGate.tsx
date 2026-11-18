import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Pencil, Sparkles } from 'lucide-react'
import { api } from '@/lib/api'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Textarea } from '@/components/ui/input'
import { ErrorState, SkeletonRows } from '@/components/ui/states'
import type { PromptDraft } from '@/types/domain'

/**
 * 提示词确认门（DESIGN.md §6.3，闸门①的逐镜形态）：
 * 粗糙描述 ─agent优化─▶ 草稿 ─确认─▶ 写入槽位（生成链路只读槽位）
 *                    └驳回─▶ 带意见回 agent 重优化
 * 未确认的草稿不进入生成链路；首帧/视频按钮在槽位为空时本就不可用（后端双保险）。
 *
 * 交互补充：
 * - 「手动修改」：用户直接编辑当前内容 → POST /drafts/manual 成新草稿 → 再确认写槽（不擅自重优化）；
 * - 槽位负向提示词以折叠 <details> 展示（与分镜表同款样式）；
 * - 「历史 N 版」条目可点击，弹框展示该版本的正/负向提示词。
 */
export default function DraftGate({ shotId }: { shotId: number }) {
  const qc = useQueryClient()
  const [rejecting, setRejecting] = useState(false)
  const [feedback, setFeedback] = useState('')
  const [manuEditing, setManuEditing] = useState(false)
  const [manualText, setManualText] = useState('')
  const [historyDraft, setHistoryDraft] = useState<PromptDraft | null>(null)

  const { data: drafts, isLoading } = useQuery({
    queryKey: ['drafts', shotId],
    queryFn: () => api.drafts.list('SHOT', shotId),
  })
  const { data: shot } = useQuery({
    queryKey: ['shot', shotId],
    queryFn: () => api.shots.get(shotId),
  })

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['drafts', shotId] })
    qc.invalidateQueries({ queryKey: ['shot', shotId] })
  }

  const optimize = useMutation({
    mutationFn: (fb?: string) => api.drafts.optimize('SHOT', shotId, 'visual_prompt', fb),
    onSuccess: invalidate,
  })
  const confirm = useMutation({
    mutationFn: (id: number) => api.drafts.confirm(id),
    onSuccess: invalidate,
  })
  const reject = useMutation({
    mutationFn: (vars: { id: number; feedback: string }) =>
      api.drafts.reject(vars.id, vars.feedback),
    onSuccess: () => {
      setRejecting(false)
      invalidate()
    },
  })
  const manual = useMutation({
    mutationFn: (content: string) => api.drafts.manual(shotId, content),
    onSuccess: () => {
      setManuEditing(false)
      invalidate()
    },
  })

  const pending = (drafts ?? []).find((d) => d.state === 'DRAFT')
  const confirmed = (drafts ?? []).find((d) => d.state === 'CONFIRMED')
  const slotFilled = shot?.visualPrompt != null && shot.visualPrompt !== ''

  const startManualEdit = () => {
    // 预填当前展示的内容：待决草稿优先，其次槽位
    setManualText(pending?.content ?? shot?.visualPrompt ?? '')
    setManuEditing(true)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          ⓪ 提示词（确认门 §6.3）
          {slotFilled ? (
            <Badge tone="success">槽位已确认</Badge>
          ) : (
            <Badge tone="warning">槽位为空 · 生成不可用</Badge>
          )}
        </CardTitle>
        <div className="flex flex-wrap items-center justify-end gap-1.5">
          <Button
            variant="outline"
            size="sm"
            disabled={manuEditing}
            title="手动编辑提示词（存为新草稿，再确认写槽）"
            onClick={startManualEdit}
          >
            <Pencil size={13} /> 手动修改
          </Button>
          <Button
            variant={pending ? 'outline' : 'primary'}
            disabled={optimize.isPending}
            onClick={() => optimize.mutate(rejectFeedbackOf(drafts))}
          >
            <Sparkles size={13} />
            {pending ? '重新优化' : drafts && drafts.length > 0 ? '再优化一版' : 'agent 优化'}
          </Button>
        </div>
      </CardHeader>
      <CardContent className="space-y-2.5">
        {optimize.isError && <ErrorState message={String(optimize.error)} />}
        {manual.isError && <ErrorState message={String(manual.error)} />}
        {isLoading && <SkeletonRows rows={2} />}

        {manuEditing && (
          <div className="rounded-md border p-2.5">
            <div className="text-2xs font-medium">
              手动修改（保存后成为新草稿，需再确认才写入槽位）
            </div>
            <Textarea
              rows={4}
              className="mt-1.5 text-xs"
              value={manualText}
              onChange={(e) => setManualText(e.target.value)}
              placeholder="编辑后的提示词内容"
            />
            <div className="mt-2 flex gap-1.5">
              <Button
                variant="primary"
                size="xs"
                disabled={manual.isPending || !manualText.trim()}
                onClick={() => manual.mutate(manualText)}
              >
                存为新草稿
              </Button>
              <Button variant="ghost" size="xs" onClick={() => setManuEditing(false)}>
                取消
              </Button>
            </div>
          </div>
        )}

        {pending && (
          <div className="rounded-md border border-warning/50 bg-warning/5 p-2.5">
            <div className="flex items-center justify-between">
              <span className="text-2xs font-medium text-warning">
                草稿 v{pending.versionNo}（未确认，不进生成链路）
              </span>
              {pending.baseContent && (
                <span className="text-2xs text-muted-foreground">原始：{pending.baseContent}</span>
              )}
            </div>
            <p className="mt-1.5 whitespace-pre-wrap text-xs leading-relaxed">{pending.content}</p>
            {pending.negativePrompt && (
              <details className="mt-1 text-2xs text-muted-foreground">
                <summary className="cursor-pointer">负向提示</summary>
                <p className="mt-0.5 whitespace-pre-wrap">{pending.negativePrompt}</p>
              </details>
            )}
            {!rejecting ? (
              <div className="mt-2 flex gap-1.5">
                <Button
                  variant="success"
                  size="xs"
                  disabled={confirm.isPending}
                  onClick={() => confirm.mutate(pending.id)}
                >
                  确认写入槽位
                </Button>
                <Button
                  variant="outline"
                  size="xs"
                  onClick={() => {
                    setRejecting(true)
                    setFeedback('')
                  }}
                >
                  驳回重优化
                </Button>
              </div>
            ) : (
              <div className="mt-2 space-y-1.5">
                <Textarea
                  rows={2}
                  placeholder="修改意见（agent 将针对性重优化）"
                  value={feedback}
                  onChange={(e) => setFeedback(e.target.value)}
                />
                <div className="flex gap-1.5">
                  <Button
                    variant="destructive"
                    size="xs"
                    disabled={reject.isPending || !feedback.trim()}
                    onClick={() => reject.mutate({ id: pending.id, feedback })}
                  >
                    确认驳回
                  </Button>
                  <Button variant="ghost" size="xs" onClick={() => setRejecting(false)}>
                    取消
                  </Button>
                </div>
              </div>
            )}
          </div>
        )}

        {!pending && confirmed && (
          <div className="rounded-md border p-2.5">
            <div className="text-2xs text-muted-foreground">
              已确认 v{confirmed.versionNo}
              {confirmed.confirmedAt ? ` · ${confirmed.confirmedAt}` : ''}
            </div>
            <p className="mt-1 whitespace-pre-wrap text-xs leading-relaxed">{confirmed.content}</p>
            {confirmed.negativePrompt && (
              <details className="mt-1 text-2xs text-muted-foreground">
                <summary className="cursor-pointer">负向提示</summary>
                <p className="mt-0.5 whitespace-pre-wrap">{confirmed.negativePrompt}</p>
              </details>
            )}
          </div>
        )}

        {!pending && !confirmed && !isLoading && (
          <div className="text-2xs text-muted-foreground">
            <p>
              当前槽位内容：{slotFilled ? shot?.visualPrompt : '（空）'}
              ——点右上「agent 优化」把粗糙描述升级为生成级提示词草稿，或「手动修改」直接编辑。
            </p>
            {shot?.negativePrompt && (
              <details className="mt-1">
                <summary className="cursor-pointer">负向提示</summary>
                <p className="mt-0.5 whitespace-pre-wrap">{shot.negativePrompt}</p>
              </details>
            )}
          </div>
        )}

        {(drafts ?? []).length > 1 && (
          <details className="text-2xs text-muted-foreground">
            <summary className="cursor-pointer">历史 {drafts!.length} 版</summary>
            <ul className="mt-1 space-y-1">
              {drafts!.map((d: PromptDraft) => (
                <li key={d.id}>
                  <button
                    className="text-left underline-offset-2 hover:underline"
                    title="点击查看该版本正/负向提示词"
                    onClick={() => setHistoryDraft(d)}
                  >
                    v{d.versionNo}{' '}
                    <Badge
                      tone={
                        d.state === 'CONFIRMED'
                          ? 'success'
                          : d.state === 'REJECTED'
                            ? 'destructive'
                            : 'warning'
                      }
                    >
                      {d.state}
                    </Badge>
                    {d.feedback ? ` · 意见：${d.feedback}` : ''}
                  </button>
                </li>
              ))}
            </ul>
          </details>
        )}
      </CardContent>

      {historyDraft && (
        <HistoryDialog draft={historyDraft} onClose={() => setHistoryDraft(null)} />
      )}
    </Card>
  )
}

/** 历史版本详情弹框：展示该版本的正向 + 负向提示词。 */
function HistoryDialog({ draft, onClose }: { draft: PromptDraft; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center bg-black/40 p-4 pt-[8vh]"
      onClick={onClose}
    >
      <div
        className="max-h-[84vh] w-full max-w-lg overflow-y-auto rounded-lg border bg-card p-4 shadow-pop"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-3 flex items-center justify-between gap-2">
          <h3 className="text-sm font-semibold">
            历史版本 v{draft.versionNo}{' '}
            <Badge
              tone={
                draft.state === 'CONFIRMED'
                  ? 'success'
                  : draft.state === 'REJECTED'
                    ? 'destructive'
                    : 'warning'
              }
            >
              {draft.state}
            </Badge>
          </h3>
          <Button variant="ghost" size="xs" onClick={onClose}>
            关闭
          </Button>
        </div>
        <div className="space-y-3 text-xs">
          <div>
            <div className="text-2xs text-muted-foreground">正向提示词</div>
            <p className="mt-1 whitespace-pre-wrap leading-relaxed">
              {draft.content || '（空）'}
            </p>
          </div>
          <div>
            <div className="text-2xs text-muted-foreground">负向提示词</div>
            <p className="mt-1 whitespace-pre-wrap leading-relaxed">
              {draft.negativePrompt || '（无）'}
            </p>
          </div>
          {draft.feedback && (
            <div>
              <div className="text-2xs text-muted-foreground">驳回意见</div>
              <p className="mt-1 whitespace-pre-wrap leading-relaxed">{draft.feedback}</p>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

/** 重新优化时带上最近一次驳回意见（若有）。 */
function rejectFeedbackOf(drafts: PromptDraft[] | undefined): string | undefined {
  const last = (drafts ?? []).find((d) => d.state === 'REJECTED' && d.feedback)
  return last?.feedback
}
