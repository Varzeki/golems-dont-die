# Starts the LongSim shards detached and at Idle priority. Called by dev-tools/longsim.sh, which
# does the copying and compiling; kept as a file of its own because quoting a Start-Process call
# through sh, PowerShell and java in one line is a losing game.
param(
	[Parameter(Mandatory = $true)][string]$Java,
	[Parameter(Mandatory = $true)][string]$Work,
	[Parameter(Mandatory = $true)][string]$Cp,
	[Parameter(Mandatory = $true)][string]$Profile,
	[Parameter(Mandatory = $true)][string]$Tag,
	[Parameter(Mandatory = $true)][int]$Shards,
	[Parameter(Mandatory = $true)][int]$Golems,
	[Parameter(Mandatory = $true)][long]$Ticks,
	[Parameter(Mandatory = $true)][int]$SnapshotTicks
)

$Work = $Work -replace '/', '\'
$Cp = $Cp -replace '/', '\'

1..$Shards | ForEach-Object {
	$out = Join-Path $Work "$Tag-$_.csv"
	$log = Join-Path $Work "$Tag-$_.log"
	$p = Start-Process -FilePath $Java -PassThru -WindowStyle Hidden `
		-RedirectStandardOutput $log -RedirectStandardError "$log.err" `
		-ArgumentList '-Xmx2g', '-cp', $Cp, 'com.golemsdontdie.LongSim',
			$Profile, "$Golems", "$Ticks", "$SnapshotTicks", "$_", $out
	$p.PriorityClass = 'Idle'
	Write-Output "  shard $_ pid $($p.Id) -> $out"
}
