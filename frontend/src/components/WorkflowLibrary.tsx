import { useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, type WorkflowImportReport } from '@/lib/api'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Table, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { EmptyState, ErrorState, SkeletonRows } from '@/components/ui/states'

/**
 * 工作流库页：列出内置/用户模板，上传导入 ComfyUI API 格式 JSON。
 * 导入管线在后端：校验 → 自动参数化 → 槽提取 → 2^k 剪枝自检 → 落盘（失败自动回滚）。
 * 注意：导入需要本机 ComfyUI 在线（节点类型经 /object_info 解析）。
 */
export default function WorkflowLibrary() {
  const qc = useQueryClient()
  const fileRef = useRef<HTMLInputElement>(null)
  const [name, setName] = useState('')
  const [report, setReport] = useState<WorkflowImportReport | null>(null)
  const [formError, setFormError] = useState<string | null>(null)

  const { data: entries, isLoading, error, refetch } = useQuery({
    queryKey: ['workflows'],
    queryFn: api.workflows.list,
  })

  const importMutation = useMutation({
    mutationFn: async (vars: { name: string; content: string }) =>
      api.workflows.import(vars.name, vars.content),
    onSuccess: (r) => {
      setReport(r)
      setFormError(null)
      if (r.ok) qc.invalidateQueries({ queryKey: ['workflows'] })
    },
    onError: (e) => {
      setReport(null)
      setFormError(String((e as Error).message || e))
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (n: string) => api.workflows.remove(n),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['workflows'] }),
    onError: (e) => setFormError(String((e as Error).message || e)),
  })

  async function onFileChosen(file: File | undefined) {
    if (!file) return
    setFormError(null)
    // 默认模板名 = 文件名（去 .json）
    if (!name.trim()) {
      setName(file.name.replace(/\.json$/i, '').replace(/[^A-Za-z0-9_/-]/g, '_'))
    }
    const content = await file.text()
    importMutation.mutate({ name: name.trim() || file.name.replace(/\.json$/i, ''), content })
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>导入工作流</CardTitle>
          <Badge tone="outline">需 ComfyUI 在线</Badge>
        </CardHeader>
        <CardContent className="space-y-3">
          <p className="text-xs leading-relaxed text-muted-foreground">
            在 ComfyUI 里把想要的流程全部填好（模型、尺寸、提示词随意），开启 Dev Mode 后用
            「Save (API Format)」导出 JSON，再从这里上传。后端会自动参数化（seed/尺寸/提示词/首尾帧等）、
            提取可选槽（LoRA、音频…）并做剪枝自检。
          </p>
          <div className="flex flex-wrap items-end gap-2">
            <label className="space-y-1">
              <div className="text-2xs text-muted-foreground">
                模板名（可带一层目录，如 sd/myflow）
              </div>
              <Input
                className="w-56"
                placeholder="myflow"
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </label>
            <input
              ref={fileRef}
              type="file"
              accept=".json,application/json"
              className="block text-xs text-muted-foreground file:mr-2 file:rounded-md file:border-0 file:bg-secondary file:px-2.5 file:py-1.5 file:text-xs file:font-medium file:text-secondary-foreground hover:file:bg-secondary/80"
              onChange={(e) => onFileChosen(e.target.files?.[0])}
            />
            {importMutation.isPending && (
              <span className="text-2xs text-muted-foreground">导入中（含剪枝自检，稍候）…</span>
            )}
          </div>
          {formError && <ErrorState message={formError} />}
        </CardContent>
      </Card>

      {report && (
        <Card className={report.ok ? 'border-success/50' : 'border-destructive/50'}>
          <CardHeader>
            <CardTitle className={report.ok ? 'text-success' : 'text-destructive'}>
              {report.ok ? `✅ 导入成功：${report.name}` : `❌ 导入被拒绝：${report.name}`}
            </CardTitle>
            <Button variant="ghost" size="xs" onClick={() => setReport(null)}>
              关闭
            </Button>
          </CardHeader>
          <CardContent className="space-y-2">
            {!report.ok && report.problems.length > 0 && (
              <ul className="list-disc space-y-1 pl-5 text-xs text-destructive">
                {report.problems.map((p, i) => (
                  <li key={i}>{p}</li>
                ))}
              </ul>
            )}
            {report.slots.length > 0 && (
              <div className="flex flex-wrap items-center gap-1.5 text-xs">
                <span className="text-muted-foreground">可选槽：</span>
                {report.slots.map((s) => (
                  <Badge key={s.name} tone="primary" className="font-mono">
                    {s.name}:{s.type}({s.sourceKind})
                  </Badge>
                ))}
              </div>
            )}
            {report.substitutions.length > 0 && (
              <details className="text-xs">
                <summary className="cursor-pointer text-muted-foreground">
                  自动参数化 {report.substitutions.length} 处
                </summary>
                <ul className="mt-1 list-disc pl-5 font-mono text-2xs">
                  {report.substitutions.map((s, i) => (
                    <li key={i}>{s}</li>
                  ))}
                </ul>
              </details>
            )}
            {report.notes.length > 0 && (
              <details className="text-xs">
                <summary className="cursor-pointer text-muted-foreground">
                  备注 {report.notes.length} 条
                </summary>
                <ul className="mt-1 list-disc pl-5 text-2xs">
                  {report.notes.map((s, i) => (
                    <li key={i}>{s}</li>
                  ))}
                </ul>
              </details>
            )}
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle>模板库</CardTitle>
          <span className="text-2xs text-muted-foreground">
            {entries ? `${entries.length} 个模板` : ''}
          </span>
        </CardHeader>
        <CardContent>
          {isLoading && <SkeletonRows rows={4} />}
          {error && <ErrorState message={`读取模板库失败：${String(error)}`} onRetry={() => refetch()} />}
          {entries && entries.length === 0 && <EmptyState title="暂无模板" />}
          {entries && entries.length > 0 && (
            <Table>
              <THead>
                <TR>
                  <TH className="w-[38%]">模板名</TH>
                  <TH className="w-[10%]">来源</TH>
                  <TH className="w-[32%]">可选槽</TH>
                  <TH className="w-[14%]">导入时间</TH>
                  <TH className="w-[6%]">操作</TH>
                </TR>
              </THead>
              <TBody>
                {entries.map((e) => (
                  <TR key={e.source + ':' + e.name}>
                    <TD className="font-mono text-xs">{e.name}</TD>
                    <TD>
                      <Badge tone={e.source === 'builtin' ? 'outline' : 'primary'}>
                        {e.source === 'builtin' ? '内置' : '用户导入'}
                      </Badge>
                      {e.overridden && (
                        <Badge tone="warning" className="ml-1">
                          覆盖内置
                        </Badge>
                      )}
                    </TD>
                    <TD>
                      {e.slots.length === 0 ? (
                        <span className="text-2xs text-muted-foreground">—</span>
                      ) : (
                        <div className="flex flex-wrap gap-1">
                          {e.slots.map((s) => (
                            <Badge key={s.name} className="font-mono">
                              {s.name}
                            </Badge>
                          ))}
                        </div>
                      )}
                    </TD>
                    <TD className="text-2xs text-muted-foreground">
                      {e.importedAt ? new Date(e.importedAt).toLocaleString() : '—'}
                    </TD>
                    <TD>
                      {e.source === 'user' && (
                        <Button
                          variant="ghost"
                          size="xs"
                          className="text-destructive hover:bg-destructive/10 hover:text-destructive"
                          disabled={deleteMutation.isPending}
                          onClick={() => {
                            if (confirm(`删除用户模板 ${e.name}？`)) deleteMutation.mutate(e.name)
                          }}
                        >
                          删除
                        </Button>
                      )}
                    </TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
