[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)]
    [int]$BridgePort = 0,

    [ValidateSet(443, 8443, 10000)]
    [int]$HttpsPort = 443,

    [string]$TailscaleExe,

    [string]$ExpectedNodeDnsName,

    [switch]$Apply
)

$ErrorActionPreference = 'Stop'

if ($BridgePort -eq 0) {
    $bridgePortSetting = [Environment]::GetEnvironmentVariable('WYNN_AI_BRIDGE_PORT')
    if ([string]::IsNullOrWhiteSpace($bridgePortSetting)) {
        $BridgePort = 8765
    } elseif (-not [int]::TryParse($bridgePortSetting, [ref]$BridgePort) -or $BridgePort -lt 1024 -or $BridgePort -gt 65535) {
        throw 'WYNN_AI_BRIDGE_PORT must be an integer from 1024 through 65535.'
    }
}

if ([string]::IsNullOrWhiteSpace($TailscaleExe)) {
    $tailscaleCommand = Get-Command -Name 'tailscale.exe' -ErrorAction SilentlyContinue
    if ($tailscaleCommand) {
        $TailscaleExe = $tailscaleCommand.Source
    } else {
        $programFiles = [Environment]::GetEnvironmentVariable('ProgramFiles')
        if ([string]::IsNullOrWhiteSpace($programFiles)) {
            throw 'Tailscale was not found on PATH and ProgramFiles is not set.'
        }
        $TailscaleExe = Join-Path $programFiles 'Tailscale\tailscale.exe'
    }
}

if (-not (Test-Path -LiteralPath $TailscaleExe -PathType Leaf)) {
    throw "Tailscale CLI was not found at '$TailscaleExe'. Pass -TailscaleExe with the installed CLI path."
}
$TailscaleExe = (Resolve-Path -LiteralPath $TailscaleExe).Path

$tailscaleStateOutput = & $TailscaleExe status --json 2>$null
if ($LASTEXITCODE -ne 0) {
    throw 'Tailscale is not connected. Sign in to the intended tailnet first, then rerun this script.'
}

try {
    $tailscaleState = ($tailscaleStateOutput | Out-String).Trim() | ConvertFrom-Json -ErrorAction Stop
} catch {
    throw 'Tailscale returned an unreadable status. No Funnel configuration was changed.'
}

if ($tailscaleState.BackendState -ne 'Running') {
    throw "Tailscale state is '$($tailscaleState.BackendState)'. Sign in to the intended tailnet first."
}

$dnsName = ([string]$tailscaleState.Self.DNSName).Trim().TrimEnd('.')
if ([string]::IsNullOrWhiteSpace($dnsName) -or -not $dnsName.EndsWith('.ts.net', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'This node has no Tailscale .ts.net DNS name. Confirm the intended tailnet and enable MagicDNS/HTTPS in its Admin Console.'
}
if ($Apply) {
    if ([string]::IsNullOrWhiteSpace($ExpectedNodeDnsName)) {
        throw 'Pass -ExpectedNodeDnsName with the exact node DNS name you verified in the Admin Console before publishing.'
    }
    if (-not $dnsName.Equals($ExpectedNodeDnsName.Trim().TrimEnd('.'), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The connected Tailscale node does not match -ExpectedNodeDnsName. No Funnel configuration was changed.'
    }
}

$funnelStatusOutput = & $TailscaleExe funnel status --json 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Could not read existing Funnel configuration: $($funnelStatusOutput -join ' ')"
}
$existingFunnelConfig = ($funnelStatusOutput | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($existingFunnelConfig) -or $existingFunnelConfig -notmatch '^\{\s*\}$') {
    throw 'An existing Funnel configuration was found or could not be verified. Review `tailscale funnel status --json` before changing it.'
}

$serveStatusOutput = & $TailscaleExe serve status --json 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Could not read existing Serve configuration: $($serveStatusOutput -join ' ')"
}
$existingServeConfig = ($serveStatusOutput | Out-String).Trim()
if ([string]::IsNullOrWhiteSpace($existingServeConfig) -or $existingServeConfig -notmatch '^\{\s*\}$') {
    throw 'An existing Serve configuration was found or could not be verified. Review `tailscale serve status --json` before changing it.'
}

$publicUrl = "https://$dnsName`:$HttpsPort/mcp"
if ($HttpsPort -eq 443) {
    $publicUrl = "https://$dnsName/mcp"
}
$origin = "http://127.0.0.1:$BridgePort/mcp"
$bridgeHostSetting = $dnsName
if ($HttpsPort -ne 443) {
    $bridgeHostSetting = "$dnsName`:$HttpsPort"
}

if (-not $Apply) {
    Write-Output "Tailnet node: $dnsName"
    Write-Output "Planned public URL: $publicUrl"
    Write-Output "Only mount: external /mcp -> $origin (the mount prefix is removed, and the target path restores /mcp at the Bridge)"
    Write-Output "Bridge config value: tailscaleHost=$bridgeHostSetting"
    Write-Output 'No change was made. Rerun with -Apply after confirming the tailnet and Funnel policy.'
    exit 0
}

$funnelArguments = @(
    'funnel',
    "--https=$HttpsPort",
    '--set-path=/mcp',
    '--bg',
    $origin
)
$funnelOutput = & $TailscaleExe @funnelArguments 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Tailscale did not apply the Funnel configuration: $($funnelOutput -join ' ')"
}

Write-Output ($funnelOutput -join "`n")
Write-Output "Configured public URL: $publicUrl"
Write-Output "Set tailscaleHost=$bridgeHostSetting in the Bridge properties file, then restart Minecraft."
Write-Output 'Confirm only the /mcp mapping is listed by `tailscale funnel status`.'
