$pids = (Get-NetTCPConnection -LocalPort 8445 -ErrorAction SilentlyContinue).OwningProcess | Sort-Object -Unique
if ($pids) {
    $pids | ForEach-Object {
        Write-Host "killing pid $_"
        Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue
    }
} else {
    Write-Host "no listener on 8445"
}
