import { useEffect, useRef, useState } from 'react'

export interface ProgressEventMsg {
  type:
    | 'started'
    | 'progress'
    | 'completed'
    | 'failed'
    | 'compose_started'
    | 'compose_progress'
    | 'compose_completed'
    | 'compose_failed'
  /** 合成事件的项目维度 id（镜头事件为 null） */
  projectId?: number | null
  versionId?: number | null
  shotId?: number | null
  comfyPromptId?: string | null
  percent?: number
  /** 合成进度时为阶段名 concat | mix | subtitle */
  message?: string
  artifactPath?: string
}

/**
 * 订阅后端 /ws/events 生成进度。自动重连。
 * onEvent 回调收到 ProgressEventMsg。
 */
export function useProgressEvents(onEvent: (e: ProgressEventMsg) => void) {
  const cbRef = useRef(onEvent)
  cbRef.current = onEvent
  const [connected, setConnected] = useState(false)

  useEffect(() => {
    let ws: WebSocket | null = null
    let retry = 2000
    let closed = false

    const connect = () => {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      ws = new WebSocket(`${proto}://${location.host}/ws/events`)
      ws.onopen = () => {
        setConnected(true)
        retry = 2000
      }
      ws.onmessage = (ev) => {
        try {
          cbRef.current(JSON.parse(ev.data) as ProgressEventMsg)
        } catch {
          // ignore malformed
        }
      }
      ws.onclose = () => {
        setConnected(false)
        if (!closed) setTimeout(connect, retry)
        retry = Math.min(retry * 2, 30000)
      }
    }
    connect()
    return () => {
      closed = true
      ws?.close()
    }
  }, [])

  return connected
}
