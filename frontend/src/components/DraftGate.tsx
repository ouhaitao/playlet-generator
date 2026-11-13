import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Sparkles } from 'lucide-react'
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
 */
export default function DraftGate({ shotId }: { shotId: number }) {
  const qc = useQueryClient()
  const [rejecting, setRejecting] = useState(false)
  const [feedback, setFeedback] = useState('')

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

  const pending = (drafts ?? []).find((d) => d.state === 'DRAFT')
  const confirmed = (drafts ?? []).find((d) => d.state === 'CONFIRMED')
  const slotFilled = shot?.visualPrompt != null && shot.visualPrompt !== ''

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
        <Button
          variant={pending ? 'outline' : 'primary'}
          disabled={optimize.isPending}
          onClick={() => optimize.mutate(rejectFeedbackOf(drafts))}
        >
          <Sparkles size={13} />
          {pending ? '重新优化' : drafts && drafts.length > 0 ? '再优化一版' : 'agent 优化'}
        </Button>
      </CardHeader>
      <CardContent className="space-y-2.5">
        {optimize.isError && <ErrorState message={String(optimize.error)} />}
        {isLoading && <SkeletonRows rows={2} />}

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
          </div>
        )}

        {!pending && !confirmed && !isLoading && (
          <p className="text-2xs text-muted-foreground">
            当前槽位内容：{slotFilled ? shot?.visualPrompt : '（空）'}
            ——点右上「agent 优化」把粗糙描述升级为生成级提示词草稿。
          </p>
        )}

        {(drafts ?? []).length > 1 && (
          <details className="text-2xs text-muted-foreground">
            <summary className="cursor-pointer">历史 {drafts!.length} 版</summary>
            <ul className="mt-1 space-y-1">
              {drafts!.map((d: PromptDraft) => (
                <li key={d.id}>
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
                </li>
              ))}
            </ul>
          </details>
        )}
      </CardContent>
    </Card>
  )
}

/** 重新优化时带上最近一次驳回意见（若有）。 */
function rejectFeedbackOf(drafts: PromptDraft[] | undefined): string | undefined {
  const last = (drafts ?? []).find((d) => d.state === 'REJECTED' && d.feedback)
  return last?.feedback
}
