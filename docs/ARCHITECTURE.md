# Garuda Architecture

Plan Pack §2 layered map, mapped to code:

```
┌─────────────────────────────────────────────────────────┐
│ UI: ChatDrawer • Dashboard • Settings • BrowserScreen   │  app/ui/
├─────────────────────────────────────────────────────────┤
│ AGENT ORCHESTRATOR                                      │
│ Task queue (pump) • State machine • Step loop •         │  agent/runtime/
│ Memory/Compaction • Scheduler • Human-in-loop           │  ServiceLocator
├──────────────────┬──────────────────────────────────────┤
│ PROVIDER GATEWAY │  PERCEPTION LAYER                    │
│ Multi-LLM        │  JS crawl + marks e1..eN (stable)    │  agent/llm/
│ Auto-detect      │  PageState serializer (token budget) │  agent/perception/
│ Tool-call norm   │  SoM renderer (vision mode)          │
├──────────────────┴──────────────────────────────────────┤
│ ACTION LAYER (CDP Client — trusted input)               │
│ click • type • scroll • extract • navigate • tabs …     │  agent/action/
├─────────────────────────────────────────────────────────┤
│ cdp-bridge: abstract unix socket → HTTP → WebSocket →   │  cdp-bridge/
│ CDP domains (Page/Runtime/DOMSnapshot/Input/Net/Emul)   │
├─────────────────────────────────────────────────────────┤
│ ENGINE: WebView (prototype) | Brave fork (@garuda-      │  browser/ fork/
│ devtools) — same CDP surface, engine-agnostic layer     │
│ Foreground Service • Room DB • Keystore                 │  agent/runtime/
└─────────────────────────────────────────────────────────┘
```

## The agent loop (plan §2)

```
while (task belum selesai && budget belum habis):
    state = perceive(tab)          # Perception.observe → marked PageState
    action = LLM(task, history, state)   # one tool call per turn
    result = execute(action)       # ActionExecutor via CDP trusted input
    verify(result)                 # page signature before/after + audit
    history.compact()              # LLM summary when context > threshold
```

## Key decisions

1. **One CDP session per tab.** `BrowserEngine.cdpSessionFor` matches the tab's
   WebView to a `/json/list` target and keeps a persistent `CdpTabSession`.
2. **Stable marks.** Perception writes `data-garuda-mark="eN"` into the live
   DOM; ids survive scroll/re-render, so the LLM's markId references stay valid.
3. **Trusted input only.** All clicks/typing go through `Input.dispatch*`;
   JS is used for observation (crawl/extract) — never for acting.
4. **Risk gate.** `ToolSchemas.isRiskyClick` + user setting route submit/pay/
   delete-like actions through `ask_human` (task → WAITING_HUMAN, notification).
5. **Crash-safe.** Every step is a Room row; unfinished tasks re-queue on boot
   (`BootResumeReceiver` + `resumeUnfinishedTasks`), compaction summary is
   checkpointed on the task row.
6. **Engine swap path.** `DevToolsLocator` prefers `@garuda-devtools` (fork)
   over `@webview_devtools_remote_<pid>` — the whole layer above is unchanged
   when the fork lands (fork/README.md).
