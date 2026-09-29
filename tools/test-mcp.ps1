param(
    [string]$BaseUrl = "http://127.0.0.1:8765",
    [string]$Token = ""
)

$commonHeaders = @{}
if ($Token) { $commonHeaders["Authorization"] = "Bearer $Token" }

function Invoke-McpModern([int]$Id, [string]$Method, [hashtable]$Params, [string]$Name = "") {
    $headers = @{}
    foreach ($k in $commonHeaders.Keys) { $headers[$k] = $commonHeaders[$k] }
    $headers["MCP-Protocol-Version"] = "2026-07-28"
    $headers["Mcp-Method"] = $Method
    if ($Name) { $headers["Mcp-Name"] = $Name }

    if (-not $Params.ContainsKey("_meta")) { $Params["_meta"] = @{} }
    $Params["_meta"]["io.modelcontextprotocol/protocolVersion"] = "2026-07-28"
    $Params["_meta"]["io.modelcontextprotocol/clientInfo"] = @{ name = "wynn-ai-bridge-test"; version = "0.5.1" }

    $body = @{ jsonrpc = "2.0"; id = $Id; method = $Method; params = $Params } | ConvertTo-Json -Depth 20
    Invoke-RestMethod -Uri "$BaseUrl/mcp" -Method Post -Headers $headers -ContentType "application/json" -Body $body
}

Write-Host "== server/discover ==" -ForegroundColor Cyan
Invoke-McpModern 1 "server/discover" @{} | ConvertTo-Json -Depth 20

Write-Host "`n== tools/list ==" -ForegroundColor Cyan
Invoke-McpModern 2 "tools/list" @{} | ConvertTo-Json -Depth 20

Write-Host "`n== minecraft_get_context ==" -ForegroundColor Cyan
Invoke-McpModern 3 "tools/call" @{ name = "minecraft_get_context"; arguments = @{ maxAgeMs = 8000; limit = 80; includeChat = $false } } "minecraft_get_context" | ConvertTo-Json -Depth 20

Write-Host "`n== minecraft_get_visible_ui ==" -ForegroundColor Cyan
Invoke-McpModern 4 "tools/call" @{ name = "minecraft_get_visible_ui"; arguments = @{ maxAgeMs = 1500; textLimit = 200; itemLimit = 150; includeTooltips = $true; includeInventory = $false; includeChat = $false } } "minecraft_get_visible_ui" | ConvertTo-Json -Depth 30

Write-Host "`n== minecraft_get_open_container ==" -ForegroundColor Cyan
Invoke-McpModern 5 "tools/call" @{ name = "minecraft_get_open_container"; arguments = @{ includeEmpty = $false; includeTooltips = $true } } "minecraft_get_open_container" | ConvertTo-Json -Depth 30

Write-Host "`n== wynn_index_status ==" -ForegroundColor Cyan
Invoke-McpModern 6 "tools/call" @{ name = "wynn_index_status"; arguments = @{} } "wynn_index_status" | ConvertTo-Json -Depth 20

Write-Host "`n== wynn_search_items(Pyrexia) ==" -ForegroundColor Cyan
Invoke-McpModern 7 "tools/call" @{ name = "wynn_search_items"; arguments = @{ query = "Pyrexia"; limit = 5 } } "wynn_search_items" | ConvertTo-Json -Depth 30
