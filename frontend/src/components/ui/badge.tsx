import type { HTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

export type BadgeTone = 'neutral' | 'primary' | 'success' | 'warning' | 'destructive' | 'outline'

const TONES: Record<BadgeTone, string> = {
  neutral: 'bg-muted text-muted-foreground',
  primary: 'bg-primary/10 text-primary',
  success: 'bg-success/10 text-success',
  warning: 'bg-warning/15 text-warning',
  destructive: 'bg-destructive/10 text-destructive',
  outline: 'border border-border text-muted-foreground',
}

/** 状态/标签徽章：11px 紧凑。 */
export function Badge({
  tone = 'neutral',
  className,
  ...props
}: HTMLAttributes<HTMLSpanElement> & { tone?: BadgeTone }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 whitespace-nowrap rounded px-1.5 py-px text-2xs font-medium',
        TONES[tone],
        className,
      )}
      {...props}
    />
  )
}
