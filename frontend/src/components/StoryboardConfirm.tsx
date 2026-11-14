import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { CheckCheck, Clapperboard, Pencil, Sparkles } from 'lucide-react'
import { api } from '@/lib/api'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Textarea } from '@/components/ui/input'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { EmptyState, ErrorState, SkeletonRows } from '@/components/ui/states'
import type { StoryboardRow } from '@/types/domain'

/**
 * 闸门①分镜表批量确认页（BACKLOG ① / DESIGN.md §6.2-6.3）。
 * 梗概 → 切镜（LLM/回退切句）→ 整表展示（序/时长/景别/运镜/草稿状态）
 * → 批量确认（逐镜确认草稿写槽）/ 逐行改（改 → 新草稿 → 再确认）
 * → 全部确认后项目状态推进 STORYBOARD_CONFIRMED。
 */
export default function StoryboardConfirm({ projectId }: { projectId: number }) {
  const qc = useQueryClient()
  const [editing, setEditing] = useState<number | null>(null)
  const [editText, setEditText] = useState('')

  const { data: rows, isLoading, error, refetch } = useQuery({
    queryKey: ['storyboard', projectId],
    queryFn: () => api.storyboard.table(projectId),
  })
  const { data: project } = useQuery({
    queryKey: ['project', projectId],
    queryFn: () => api.projects.get(projectId),
  })

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['storyboard', projectId] })
    qc.invalidateQueries({ queryKey: ['project', projectId] })
    qc.invalidateQueries({ queryKey: ['shots', projectId] })
  }

  const generate = useMutation({
    mutationFn: () => api.storyboard.generate(projectId),
    onSuccess: invalidate,
  })
  const confirmAll = useMutation({
    mutationFn: () => api.storyboard.confirmAll(projectId),
    onSuccess: invalidate,
  })
  const confirmOne = useMutation({
    mutationFn: (draftId: number) => api.drafts.confirm(draftId),
    onSuccess: invalidate,
  })
  const optimizeOne = useMutation({
    mutationFn: (shotId: number) =>
      api.drafts.optimize('SHOT', shotId, 'visual_prompt'),
    onSuccess: invalidate,
  })
  const saveEdit = useMutation({
    mutationFn: (vars: { shotId: number; content: string }) =>
      api.storyboard.editPrompt(projectId, vars.shotId, vars.content),
    onSuccess: () => {
      // 保持编辑态展开：存为新草稿后用户可立即点「确认」写槽
      invalidate()
    },
  })

  const list = rows ?? []
  const confirmedCount = list.filter((r) => r.confirmed).length
  const pendingCount = list.length - confirmedCount
  const allConfirmed = list.length > 0 && pendingCount === 0
  const projectState = project?.state
  const hasAnyGeneration = list.some((r) => r.shotState && r.shotState !== 'PENDING')

  const startEdit = (r: StoryboardRow) => {
    setEditing(r.shotId)
    setEditText(r.draftState === 'DRAFT' ? (r.draftContent ?? '') : (r.visualPrompt ?? ''))
  }

  return (
    <div className="space-y-3">
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Clapperboard size={14} /> ① 分镜表（闸门①）
            {projectState && (
              <Badge
                tone={
                  projectState === 'STORYBOARD_CONFIRMED' || projectState === 'COMPOSED'
                    ? 'success'
                    : 'warning'
                }
              >
                {projectState === 'STORYBOARD_CONFIRMED'
                  ? '分镜已定稿'
                  : projectState === 'COMPOSED'
                    ? '已成片'
                    : projectState === 'STORYBOARD'
                      ? '待确认'
                      : projectState}
              </Badge>
            )}
          </CardTitle>
          <div className="flex gap-1.5">
            <Button
              variant={list.length === 0 ? 'primary' : 'outline'}
              size="sm"
              disabled={generate.isPending || hasAnyGeneration}
              title={
                hasAnyGeneration
                  ? '已有镜头进入生成流程，不能重新切镜'
                  : list.length > 0
                    ? '重新切镜将替换当前分镜表（连同草稿）'
                    : '按剧情梗概自动切镜'
              }
              onClick={() => generate.mutate()}
            >
              <Sparkles size={13} />
              {list.length === 0 ? '自动切镜' : '重新切镜'}
            </Button>
            <Button
              variant="success"
              size="sm"
              disabled={confirmAll.isPending || list.length === 0 || allConfirmed}
              onClick={() => confirmAll.mutate()}
            >
              <CheckCheck size={13} />
              批量确认{pendingCount > 0 ? `（余 ${pendingCount}）` : ''}
            </Button>
          </div>
        </CardHeader>
        <CardContent className="space-y-2">
          {list.length > 0 && (
            <p className="text-2xs text-muted-foreground">
              已确认 {confirmedCount} / {list.length} 镜 ·
              确认 = 草稿提示词（含负向）写入槽位，进入生成链路（§6.3）
            </p>
          )}
          {generate.isError && <ErrorState message={String(generate.error)} />}
          {confirmAll.isError && <ErrorState message={String(confirmAll.error)} />}
          {generate.isSuccess && !generate.data.usedLlm && (
            <p className="rounded-md border border-warning/50 bg-warning/5 p-2 text-2xs text-warning">
              未检测到可用 LLM，本次为「按句切分」回退结果（一句一镜、默认
              5s）——建议逐镜点「优化」润色提示词、按需调整时长与景别。
            </p>
          )}
        </CardContent>
      </Card>

      {error && <ErrorState message={`分镜表加载失败：${String(error)}`} onRetry={() => refetch()} />}
      {isLoading && <SkeletonRows rows={5} />}
      {!isLoading && list.length === 0 && !error && (
        <EmptyState
          title="暂无分镜"
          hint="点上方「自动切镜」：按剧情梗概切出镜头表，每镜自动生成提示词草稿供确认。"
        />
      )}

      {list.length > 0 && (
        <Card>
          <CardContent className="p-0">
            <Table>
              <THead>
                <TR>
                  <TH className="w-10">序</TH>
                  <TH className="w-16">时长</TH>
                  <TH className="w-14">景别</TH>
                  <TH className="w-14">运镜</TH>
                  <TH>提示词（草稿 → 槽位）</TH>
                  <TH className="w-24">状态</TH>
                  <TH className="w-40">操作</TH>
                </TR>
              </THead>
              <TBody>
                {list.map((r) => (
                  <TR key={r.shotId} className="align-top">
                    <TD className="pt-2 text-xs font-semibold">#{r.ord}</TD>
                    <TD className="pt-2 text-2xs tabular-nums">
                      {r.durationSec.toFixed(2)}s
                    </TD>
                    <TD className="pt-2 text-2xs">{r.shotSize}</TD>
                    <TD className="pt-2 text-2xs">{r.cameraMove}</TD>
                    <TD className="max-w-0 pt-2">
                      {editing === r.shotId ? (
                        <div className="space-y-1.5">
                          <Textarea
                            rows={3}
                            value={editText}
                            onChange={(e) => setEditText(e.target.value)}
                            placeholder="该镜的画面描述 / 提示词"
                          />
                          <div className="flex gap-1.5">
                            <Button
                              variant="primary"
                              size="xs"
                              disabled={saveEdit.isPending || !editText.trim()}
                              onClick={() =>
                                saveEdit.mutate({ shotId: r.shotId, content: editText })
                              }
                            >
                              存为新草稿
                            </Button>
                            <Button variant="ghost" size="xs" onClick={() => setEditing(null)}>
                              取消
                            </Button>
                          </div>
                          {saveEdit.isError && (
                            <p className="text-2xs text-destructive">{String(saveEdit.error)}</p>
                          )}
                        </div>
                      ) : (
                        <div className="space-y-0.5">
                          <p className="whitespace-pre-wrap text-2xs leading-relaxed">
                            {r.draftState === 'DRAFT'
                              ? r.draftContent
                              : r.visualPrompt || '（空——点「优化」出草稿）'}
                          </p>
                          {r.dialogue && (
                            <p className="text-2xs text-muted-foreground">
                              台词{r.speaker ? `（${r.speaker}）` : ''}：{r.dialogue}
                            </p>
                          )}
                          {(r.draftState === 'DRAFT' ? r.draftNegative : r.negativePrompt) && (
                            <details className="text-2xs text-muted-foreground">
                              <summary className="cursor-pointer">负向提示</summary>
                              <p className="mt-0.5 whitespace-pre-wrap">
                                {r.draftState === 'DRAFT' ? r.draftNegative : r.negativePrompt}
                              </p>
                            </details>
                          )}
                        </div>
                      )}
                    </TD>
                    <TD className="pt-2">
                      <DraftBadge row={r} />
                    </TD>
                    <TD className="pt-2">
                      {editing !== r.shotId && (
                        <div className="flex flex-wrap gap-1">
                          {r.draftState === 'DRAFT' && r.draftId != null && (
                            <Button
                              variant="success"
                              size="xs"
                              disabled={confirmOne.isPending}
                              onClick={() => confirmOne.mutate(r.draftId!)}
                            >
                              确认
                            </Button>
                          )}
                          {r.draftState !== 'DRAFT' && (
                            <Button
                              variant="outline"
                              size="xs"
                              disabled={optimizeOne.isPending}
                              onClick={() => optimizeOne.mutate(r.shotId)}
                            >
                              {r.draftState === 'REJECTED' ? '重优化' : '优化'}
                            </Button>
                          )}
                          <Button variant="ghost" size="xs" onClick={() => startEdit(r)}>
                            <Pencil size={11} /> 改
                          </Button>
                        </div>
                      )}
                    </TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  )
}

/** 行内状态徽标：草稿态优先，其次已确认/驳回/无草稿。 */
function DraftBadge({ row }: { row: StoryboardRow }) {
  if (row.draftState === 'DRAFT') {
    return <Badge tone="warning">草稿 v{row.draftVersion}</Badge>
  }
  if (row.draftState === 'REJECTED') {
    return <Badge tone="destructive">已驳回 v{row.draftVersion}</Badge>
  }
  if (row.confirmed) {
    return (
      <Badge tone="success">
        已确认{row.draftVersion > 0 ? ` v${row.draftVersion}` : ''}
      </Badge>
    )
  }
  return <Badge tone="neutral">无草稿</Badge>
}
