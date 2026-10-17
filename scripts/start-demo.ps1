<#
.SYNOPSIS
    Starts the complete local demo: Hardhat node, EnergyTrading contract and the Spring Boot backend
    with the dashboard at http://localhost:8082.

.DESCRIPTION
    1. Checks Java, Maven, Node.js and the MySQL port.
    2. Starts a Hardhat node in its own window if none is listening on 8545 (close that window to
       simulate a blockchain outage).
    3. Reuses the deployment in blockchain/deployments/localhost.json when it is still live on the
       node; otherwise deploys a new contract with Hardhat dev account #1 as the recorder.
    4. Builds the backend if needed and runs it in this window (Ctrl+C stops it cleanly).

    Secrets are never printed. The database password is taken from DB_PASSWORD (or, if unset, from
    the local Docker container named by -MySqlContainer). The privacy pseudonym key is taken from
    PRIVACY_PSEUDONYM_KEY; if it is not set anywhere, a random key is generated once and saved as a
    user environment variable so pseudonyms stay stable across restarts.

.EXAMPLE
    ./scripts/start-demo.ps1
.EXAMPLE
    ./scripts/start-demo.ps1 -DbName energy_trading -Rebuild
.EXAMPLE
    ./scripts/start-demo.ps1 -NoBlockchain     # marketplace only; trades stay PENDING
#>
[CmdletBinding()]
param(
    [string]$DbName = $(if ($env:DB_NAME) { $env:DB_NAME } else { "energy_trading" }),
    [string]$DbHost = $(if ($env:DB_HOST) { $env:DB_HOST } else { "localhost" }),
    [int]$DbPort = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 3306 }),
    [string]$MySqlContainer = "mysql-energy",
    [int]$ServerPort = 8082,
    [switch]$NoBlockchain,
    [switch]$Rebuild
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Chain = Join-Path $Root "blockchain"
$Jar = Join-Path $Root "backend/target/energy-trading-backend-0.0.1-SNAPSHOT.jar"

function Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Info($text) { Write-Host "    $text" }
function Fail($text) { Write-Host "`nERROR: $text" -ForegroundColor Red; exit 1 }

function Test-Port([string]$hostName, [int]$port) {
    # Try every address of the host (localhost may resolve to ::1 and 127.0.0.1) with a matching socket
    try { $addresses = [System.Net.Dns]::GetHostAddresses($hostName) } catch { return $false }
    foreach ($address in $addresses) {
        $client = New-Object System.Net.Sockets.TcpClient($address.AddressFamily)
        try {
            $task = $client.ConnectAsync($address, $port)
            if ($task.Wait(1500) -and $client.Connected) { return $true }
        } catch { } finally { $client.Dispose() }
    }
    return $false
}

function Get-Setting([string]$name) {
    foreach ($scope in "Process", "User", "Machine") {
        $value = [Environment]::GetEnvironmentVariable($name, $scope)
        if ($value) { return $value }
    }
    return $null
}

# ---------------------------------------------------------------- prerequisites
Step "Checking prerequisites"
foreach ($tool in "java", "mvn", "node", "npm") {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { Fail "'$tool' was not found on PATH (see README, Prerequisites)" }
}
$javaVersion = (& java -version 2>&1 | Select-Object -First 1).ToString()
Info "Java: $javaVersion"
if (Test-Port "127.0.0.1" $ServerPort) {
    Fail "Port $ServerPort is already in use (is the backend already running?). Stop it with Ctrl+C in its window, then run this script again."
}
if (-not (Test-Port $DbHost $DbPort)) {
    Fail "MySQL is not reachable at ${DbHost}:${DbPort}. Start it first (e.g. 'docker start $MySqlContainer')."
}
Info "MySQL reachable at ${DbHost}:${DbPort}, database '$DbName'"

# ---------------------------------------------------------------- secrets (never printed)
$dbPassword = Get-Setting "DB_PASSWORD"
if (-not $dbPassword -and (Get-Command docker -ErrorAction SilentlyContinue)) {
    $dbPassword = (& docker exec $MySqlContainer printenv MYSQL_ROOT_PASSWORD 2>$null)
    if ($dbPassword) { Info "Database password read from Docker container '$MySqlContainer'" }
}
if (-not $dbPassword) {
    $secure = Read-Host "MySQL password for user '$(if ($env:DB_USERNAME) { $env:DB_USERNAME } else { 'root' })'" -AsSecureString
    $dbPassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}

$privacyKey = Get-Setting "PRIVACY_PSEUDONYM_KEY"
if (-not $privacyKey) {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $privacyKey = -join ($bytes | ForEach-Object { $_.ToString("x2") })
    [Environment]::SetEnvironmentVariable("PRIVACY_PSEUDONYM_KEY", $privacyKey, "User")
    Info "Generated a privacy pseudonym key and saved it as the user environment variable PRIVACY_PSEUDONYM_KEY (value not shown)."
    Info "Keep it stable: changing it changes every household pseudonym."
}

$env:DB_NAME = $DbName
$env:DB_HOST = $DbHost
$env:DB_PORT = "$DbPort"
$env:DB_PASSWORD = $dbPassword
$env:PRIVACY_PSEUDONYM_KEY = $privacyKey
$env:SERVER_PORT = "$ServerPort"
if (-not $env:JPA_DDL_AUTO) { $env:JPA_DDL_AUTO = "validate" }

# ---------------------------------------------------------------- blockchain
if ($NoBlockchain) {
    $env:BLOCKCHAIN_ENABLED = "false"
    Step "Blockchain disabled (-NoBlockchain): trades are reserved but cannot be confirmed"
} else {
    Step "Preparing the local blockchain"
    if (-not (Test-Path (Join-Path $Chain "node_modules"))) {
        Info "Installing Hardhat dependencies (first run only)..."
        Push-Location $Chain; try { & npm install --no-fund --no-audit | Out-Null } finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { Fail "npm install failed in blockchain/" }
    }
    if (-not (Test-Port "127.0.0.1" 8545)) {
        Info "Starting a Hardhat node in a new window (close that window to simulate a blockchain outage)"
        Start-Process -FilePath "powershell.exe" -WorkingDirectory $Chain `
            -ArgumentList "-NoExit", "-Command", "`$Host.UI.RawUI.WindowTitle = 'Hardhat node (port 8545)'; npx hardhat node" | Out-Null
        $deadline = (Get-Date).AddSeconds(60)
        while (-not (Test-Port "127.0.0.1" 8545)) {
            if ((Get-Date) -gt $deadline) { Fail "The Hardhat node did not start within 60 seconds" }
            Start-Sleep -Milliseconds 700
        }
    } else {
        Info "Hardhat node already running on port 8545"
    }

    function Read-DemoEnv {
        Push-Location $Chain
        try { $out = & npx hardhat run scripts/demo-env.js --network localhost 2>&1 } finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { Fail "Could not inspect the local chain: $($out | Select-Object -Last 1)" }
        $line = $out | Where-Object { "$_".StartsWith("{") } | Select-Object -Last 1
        return ($line | ConvertFrom-Json)
    }

    $demo = Read-DemoEnv
    if ($demo.deployment -ne "valid") {
        if ($demo.deployment -eq "stale") { Info "The saved deployment is not live on this node (the chain was restarted); deploying a new contract" }
        else { Info "No deployment found; deploying EnergyTrading" }
        $env:RECORDER_ADDRESS = $demo.recorderAddress
        Push-Location $Chain; try { & npx hardhat run scripts/deploy.js --network localhost | ForEach-Object { Info $_ } } finally { Pop-Location }
        if ($LASTEXITCODE -ne 0) { Fail "Contract deployment failed" }
        Remove-Item Env:RECORDER_ADDRESS
        $demo = Read-DemoEnv
        if ($demo.deployment -ne "valid") { Fail "The new deployment could not be verified" }
    } else {
        Info "Reusing the live deployment at $($demo.contractAddress)"
    }
    $env:BLOCKCHAIN_ENABLED = "true"
    $env:BLOCKCHAIN_RPC_URL = "http://127.0.0.1:8545"
    $env:BLOCKCHAIN_CHAIN_ID = "31337"
    $env:CONTRACT_ADDRESS = $demo.contractAddress
    $env:BLOCKCHAIN_DEPLOYMENT_ID = $demo.deploymentId
    $env:BLOCKCHAIN_SIGNER_PRIVATE_KEY = $demo.recorderKey   # Hardhat dev account #1 (public test key)
    Info "Contract $($demo.contractAddress), deployment $($demo.deploymentId.Substring(0, 14))..., recorder $($demo.recorderAddress)"
}

# ---------------------------------------------------------------- backend
if ($Rebuild -or -not (Test-Path $Jar)) {
    Step "Building the backend (tests skipped; run 'mvn -f backend/pom.xml test' separately)"
    & mvn -q -f (Join-Path $Root "backend/pom.xml") -DskipTests package
    if ($LASTEXITCODE -ne 0) { Fail "Maven build failed" }
}

Step "Starting the backend on http://localhost:$ServerPort  (Ctrl+C to stop)"
Info "Dashboard: http://localhost:$ServerPort/   |   Health: http://localhost:$ServerPort/api/health"
Info "If startup fails with 'Schema-validation', apply database/schema.sql or the migrations first (README)."
& java -jar $Jar
