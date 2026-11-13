import type { ReactNode } from 'react'
import { Moon, Sun } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useTheme } from '@/hooks/useTheme'
import { cn } from '@/lib/utils'

export type NavKey = 'projects' | 'workflows'

const NAV: { key: NavKey; label: string }[] = [
  { key: 'projects', label: '项目' },
  { key: 'workflows', label: '工作流库' },
]

/**
 * 应用外壳：顶栏（品牌 + 主导航 + 主题切换）+ 内容区。
 * 页面增多时往 NAV 加一项即可，不再改 App 的 state 硬切。
 */
export function AppShell({
  nav,
  onNav,
  children,
}: {
  nav: NavKey
  onNav: (k: NavKey) => void
  children: ReactNode
}) {
  const { theme, toggle } = useTheme()
  return (
    <div className="flex min-h-screen flex-col">
      <header className="sticky top-0 z-10 border-b bg-card/80 backdrop-blur">
        <div className="mx-auto flex h-12 max-w-[1400px] items-center gap-6 px-4">
          <div className="flex items-baseline gap-2">
            <span className="text-sm font-bold tracking-tight">短剧生成平台</span>
            <span className="hidden text-2xs text-muted-foreground sm:inline">
              agent 驱动 · ComfyUI · 串行审核
            </span>
          </div>
          <nav className="flex items-center gap-1">
            {NAV.map((n) => (
              <button
                key={n.key}
                onClick={() => onNav(n.key)}
                className={cn(
                  'rounded-md px-2.5 py-1 text-xs font-medium transition-colors',
                  nav === n.key
                    ? 'bg-primary/10 text-primary'
                    : 'text-muted-foreground hover:bg-muted hover:text-foreground',
                )}
              >
                {n.label}
              </button>
            ))}
          </nav>
          <div className="ml-auto">
            <Button
              variant="ghost"
              size="xs"
              title={theme === 'dark' ? '切换到亮色' : '切换到暗色'}
              onClick={toggle}
            >
              {theme === 'dark' ? <Sun size={14} /> : <Moon size={14} />}
            </Button>
          </div>
        </div>
      </header>
      <main className="mx-auto w-full max-w-[1400px] flex-1 px-4 py-4">{children}</main>
    </div>
  )
}
