param(
    [string]$RunDir,
    [string]$RuntimeSamplesPath,
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-Stats {
    param(
        [object[]]$Values
    )

    if (-not $Values -or $Values.Count -eq 0) {
        return $null
    }

    $numericValues = @(
        $Values |
            Where-Object { $null -ne $_ } |
            ForEach-Object { [double]$_ }
    )

    if (-not $numericValues -or $numericValues.Count -eq 0) {
        return $null
    }

    return [ordered]@{
        min = ($numericValues | Measure-Object -Minimum).Minimum
        max = ($numericValues | Measure-Object -Maximum).Maximum
        avg = ($numericValues | Measure-Object -Average).Average
        last = $numericValues[-1]
    }
}

if (-not $RuntimeSamplesPath) {
    if (-not $RunDir) {
        throw "Provide -RunDir or -RuntimeSamplesPath."
    }
    $RuntimeSamplesPath = Join-Path $RunDir "runtime-samples.jsonl"
}

if (-not (Test-Path $RuntimeSamplesPath)) {
    throw "Runtime samples file not found: $RuntimeSamplesPath"
}

$lines = Get-Content $RuntimeSamplesPath | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$samples = @($lines | ForEach-Object { $_ | ConvertFrom-Json })

$summary = [ordered]@{
    sampleCount = $samples.Count
    firstSampleAt = if ($samples.Count -gt 0) { $samples[0].time } else { $null }
    lastSampleAt = if ($samples.Count -gt 0) { $samples[-1].time } else { $null }
    processCpuUsage = Get-Stats ($samples | ForEach-Object { $_.processCpuUsage })
    hikariPending = Get-Stats ($samples | ForEach-Object { $_.hikariPending })
    mailboxBacklog = Get-Stats ($samples | ForEach-Object { $_.mailboxBacklog })
    mailboxRejectedDelta = Get-Stats ($samples | ForEach-Object { $_.mailboxRejectedDelta })
    stripeLockTimeoutDelta = Get-Stats ($samples | ForEach-Object { $_.stripeLockTimeoutDelta })
    xaddFailDelta = Get-Stats ($samples | ForEach-Object { $_.xaddFailDelta })
    persistFallbackDelta = Get-Stats ($samples | ForEach-Object { $_.persistFallbackDelta })
    redisDegradationDelta = Get-Stats ($samples | ForEach-Object { $_.redisDegradationDelta })
    healthNotUpCount = @($samples | Where-Object { $_.health -ne "UP" }).Count
    dbHealthNotUpCount = @($samples | Where-Object { $_.dbHealth -notin @("UP", "UNKNOWN") }).Count
    redisHealthNotUpCount = @($samples | Where-Object { $_.redisHealth -notin @("UP", "UNKNOWN") }).Count
    lastHealth = if ($samples.Count -gt 0) { $samples[-1].health } else { $null }
    lastDbHealth = if ($samples.Count -gt 0) { $samples[-1].dbHealth } else { $null }
    lastRedisHealth = if ($samples.Count -gt 0) { $samples[-1].redisHealth } else { $null }
}

$json = $summary | ConvertTo-Json -Depth 8
if ($OutputPath) {
    $json | Set-Content -LiteralPath $OutputPath -Encoding utf8
}

Write-Output $json
