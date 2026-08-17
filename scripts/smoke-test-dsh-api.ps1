# Smoke test for the DeepSeek Harness HTTP API as used by the Rider plugin.
# Read-only by default: lists sessions and pulls the history of the newest one.
# Pass -Create to also exercise session.create (creates a real, empty session).
param(
    [string]$BaseUrl = 'http://127.0.0.1:3080',
    [switch]$Create
)

function Invoke-DshRpc {
    param([string]$Method, [string]$PayloadJson = '{}')
    $body = @{
        type = 'client-request'
        rpcId = [guid]::NewGuid().ToString()
        method = $Method
        payload = $PayloadJson | ConvertFrom-Json -ErrorAction SilentlyContinue
    } | ConvertTo-Json -Depth 10
    try {
        Invoke-RestMethod -Uri "$BaseUrl/api/$Method" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 30
    } catch {
        Write-Host "REQUEST FAILED: $($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
}

Write-Host "== 1. session.list ==" -ForegroundColor Cyan
$list = Invoke-DshRpc -Method 'session.list'
if (-not $list.result.ok) {
    Write-Host "list failed: $($list.result.error.message)" -ForegroundColor Red
    exit 1
}
$items = @($list.result.value.items)
Write-Host "  sessions: $($items.Count)"
$items | Select-Object -First 5 | ForEach-Object {
    Write-Host ("  - {0}  running={1} cwd={2}" -f $_.sessionId.Substring(0, [Math]::Min(16, $_.sessionId.Length)), $_.running, $_.cwd)
}

$newest = $items | Where-Object { -not $_.origin } | Sort-Object updatedAt -Descending | Select-Object -First 1
if ($newest) {
    Write-Host "`n== 2. session.history (newest: $($newest.sessionId.Substring(0,16))) ==" -ForegroundColor Cyan
    $hist = Invoke-DshRpc -Method 'session.history' -PayloadJson ('{{"sessionId":"{0}"}}' -f $newest.sessionId)
    if (-not $hist.result.ok) {
        Write-Host "history failed: $($hist.result.error.message)" -ForegroundColor Red
        exit 1
    }
    $events = @($hist.result.value.events)
    Write-Host "  events returned: $($events.Count)"
    $events | Select-Object -Last 3 | ForEach-Object { Write-Host ("  - {0} seq={1}" -f $_.event.type, $_.event.seq) }
}

if ($Create) {
    Write-Host "`n== 3. session.create ==" -ForegroundColor Cyan
    $created = Invoke-DshRpc -Method 'session.create' -PayloadJson '{}'
    if (-not $created.result.ok) {
        Write-Host "create failed: $($created.result.error.message)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  created sessionId: $($created.result.value.sessionId)"
}

Write-Host "`nOK: the harness API works from an external client, just like the Rider plugin will use it." -ForegroundColor Green
