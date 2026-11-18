import { useQuery } from '@tanstack/react-query'
import { api, type WorkflowDetail } from '@/lib/api'
import { Badge } from '@/components/ui/badge'
import { Input, Textarea } from '@/components/ui/input'
import { ErrorState, SkeletonRows } from '@/components/ui/states'
import { sortGroupsClipFirst } from '@/components/WorkflowParamPanel'
import type { WorkflowNodeGroup } from '@/lib/api'

/** 版本留档的 paramsJson 解析结果：{workflow, params: {jsonPath: value}}。 */
export interface VersionParams {
  workflow?: string
  params?: Record<string, unknown>
}

export function parseParamsJson(paramsJson?: string | null): VersionParams | null {
  if (!paramsJson || !paramsJson.trim()) return null
  try {
    return JSON.parse(paramsJson) as VersionParams
  } catch {
    return null
  }
}

/** 把留档值转成受控输入显示：布尔 → checkbox 用，其余 → 字符串。 */
function displayValue(v: unknown): string | boolean {
  if (typeof v === 'boolean') return v
  if (v == null) return ''
  return String(v)
}

/**
 * 只读工作流弹框内容（审核台「显示工作流」）：
 * 用版本的 paramsJson（{workflow, params}）拉取工作流元数据，按节点分组渲染所有参数，
 * 值 = params[jsonPath] ?? defaultValue，<b>全部输入框禁用，仅展示不可改</b>。
 * CLIPTextEncode 组同样排最前、占满整行。
 */
export default function WorkflowReadonlyView({ paramsJson }: { paramsJson?: string | null }) {
  const parsed = parseParamsJson(paramsJson)
  const workflow = parsed?.workflow
  const params = parsed?.params ?? {}

  const { data: detail, isLoading, error } = useQuery({
    queryKey: ['workflow-detail', workflow],
    queryFn: () => api.workflows.get(workflow as string),
    enabled: !!workflow,
  })

  if (!workflow) {
    return (
      <div className="text-xs text-muted-foreground">
        该版本没有留档工作流参数（paramsJson 为空或不可解析）。
      </div>
    )
  }

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone="primary">工作流：{workflow}</Badge>
        {detail?.model && <Badge tone="outline">模型：{detail.model}</Badge>}
      </div>
      {error && <ErrorState message={`读取工作流元数据失败：${String(error)}`} />}
      {isLoading && <SkeletonRows rows={3} />}
      {detail && <ReadonlyGroups detail={detail} params={params} />}
    </div>
  )
}

function ReadonlyGroups({
  detail,
  params,
}: {
  detail: WorkflowDetail
  params: Record<string, unknown>
}) {
  const groups = sortGroupsClipFirst(detail.groups) as WorkflowNodeGroup[]
  if (groups.length === 0) {
    return <p className="text-2xs text-muted-foreground">该工作流没有可展示的参数。</p>
  }
  return (
    <div className="max-h-[60vh] space-y-3 overflow-y-auto rounded-md border bg-muted/20 p-2.5">
      {groups.map((g) => {
        const isClip = g.classType === 'CLIPTextEncode'
        return (
          <details key={g.nodeId} open={isClip || g.fields.length <= 6}>
            <summary className="cursor-pointer text-xs font-medium">
              <span className="font-mono text-2xs text-muted-foreground">[{g.nodeId}]</span>{' '}
              {g.nodeTitle}
              {g.nodeTitle !== g.classType && (
                <span className="ml-1 font-mono text-2xs text-muted-foreground">{g.classType}</span>
              )}
            </summary>
            <div className={isClip ? 'mt-1.5 grid grid-cols-1 gap-2' : 'mt-1.5 grid grid-cols-1 gap-2 md:grid-cols-2'}>
              {g.fields.map((f) => {
                const raw = f.jsonPath in params ? params[f.jsonPath] : f.defaultValue
                const val = displayValue(raw)
                const longText =
                  f.kind === 'STRING' &&
                  (/prompt|text/i.test(f.inputName) ||
                    String(val).length > 60 ||
                    String(val).includes('\n'))
                return (
                  <label key={f.jsonPath} className="block space-y-0.5">
                    <div className="text-2xs text-muted-foreground">
                      <span className="font-mono">{f.inputName}</span>
                      <span className="ml-1 opacity-60">{f.kind}</span>
                    </div>
                    {f.kind === 'BOOLEAN' ? (
                      <input type="checkbox" disabled checked={val === true} className="accent-primary" />
                    ) : longText ? (
                      <Textarea
                        rows={isClip ? 5 : 3}
                        className="w-full text-xs"
                        value={String(val)}
                        disabled
                        readOnly
                      />
                    ) : (
                      <Input
                        type={f.kind === 'NUMBER' ? 'number' : 'text'}
                        className="h-7 w-full text-xs"
                        value={String(val)}
                        disabled
                        readOnly
                      />
                    )}
                  </label>
                )
              })}
            </div>
          </details>
        )
      })}
    </div>
  )
}
