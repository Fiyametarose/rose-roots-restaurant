[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('Install','Start','ReplaceDeviceCredential')][string]$Action,
    [Parameter(Mandatory=$true)][ValidateSet('Backend','Customer','Kitchen')][string]$Role,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9-]{0,31}$')][string]$Name = $Role
)

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'This setup targets Windows.' }
$installRoot = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'RoseRootsShowcase'
$homeDir = Join-Path $installRoot "$Role-$Name"
$configuration = Join-Path $homeDir 'installation.json'

function Assert-LocalPrivatePath([string]$Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    if ($absolute.StartsWith('\\')) { throw 'Credential material must be on a local disk.' }
    foreach ($syncRoot in @($env:OneDrive, $env:OneDriveConsumer, $env:OneDriveCommercial)) {
        if ($syncRoot -and ($absolute + '\').StartsWith($syncRoot.TrimEnd('\') + '\',
                [StringComparison]::OrdinalIgnoreCase)) { throw 'Credential material must be outside synced folders.' }
    }
    for ($parent = $absolute; $parent; $parent = [IO.Path]::GetDirectoryName($parent)) {
        if (Test-Path -LiteralPath (Join-Path $parent '.git')) { throw 'Credential material must be outside Git worktrees.' }
        if ((Test-Path -LiteralPath $parent) -and
            ((Get-Item -Force -LiteralPath $parent).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Credential material cannot use a redirected or linked folder.'
        }
    }
}

function Protect-Directory([string]$Path) {
    Assert-LocalPrivatePath $Path
    [IO.Directory]::CreateDirectory($Path) | Out-Null
    $acl = [IO.Directory]::GetAccessControl($Path, [Security.AccessControl.AccessControlSections]::Access)
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($rule in @($acl.Access)) { $acl.RemoveAccessRuleSpecific($rule) }
    $user = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $system = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    foreach ($principal in @($user, $system)) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($principal, 'FullControl',
            'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    [IO.Directory]::SetAccessControl($Path, $acl)
}

function Read-FilePath([string]$Prompt) {
    $path = Read-Host $Prompt
    if (!$path -or !(Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Choose an existing file.' }
    (Resolve-Path -LiteralPath $path).Path
}

function Reveal([Security.SecureString]$Secret) {
    [Net.NetworkCredential]::new('', $Secret).Password
}

function Read-Secret([string]$Prompt) {
    $secure = Read-Host $Prompt -AsSecureString
    if (!$secure -or $secure.Length -eq 0) { throw 'A value is required.' }
    $secure
}

function Read-DeviceCredential {
    $secret = Read-Secret 'Device credential issued by Manager'
    $pattern = if ($Role -eq 'Kitchen') { '^ws_[A-Za-z0-9_-]{43}$' } else { '^[A-Za-z0-9_-]{43}$' }
    if ((Reveal $secret) -cnotmatch $pattern) { throw 'The credential does not match this installation role.' }
    ConvertFrom-SecureString $secret
}

function Protect-BootstrapCredential([string]$Java, [string]$Jar) {
    $secret = Read-Secret 'Bootstrap Manager password, or an existing BCrypt hash'
    $value = Reveal $secret
    if ($value -cmatch '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$') {
        return ConvertFrom-SecureString $secret
    }
    $process = [Diagnostics.Process]::new()
    try {
        $process.StartInfo.FileName = $Java
        $process.StartInfo.Arguments = "-jar `"$Jar`" --showcase-bootstrap-hash"
        $process.StartInfo.UseShellExecute = $false
        $process.StartInfo.CreateNoWindow = $true
        $process.StartInfo.RedirectStandardInput = $true
        $process.StartInfo.RedirectStandardOutput = $true
        $null = $process.Start()
        $process.StandardInput.WriteLine([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($value)))
        $process.StandardInput.Close()
        $hash = $process.StandardOutput.ReadToEnd().Trim()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0 -or $hash -cnotmatch '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$') {
            throw 'Unable to establish the bootstrap Manager credential.'
        }
        ConvertFrom-SecureString (ConvertTo-SecureString $hash -AsPlainText -Force)
    } finally {
        $process.Dispose()
    }
}

function New-LaunchShortcut {
    $programs = [Environment]::GetFolderPath('Programs')
    $entry = Join-Path $programs "Rose Roots Showcase - $Role - $Name.lnk"
    $shell = New-Object -ComObject WScript.Shell
    $link = $shell.CreateShortcut($entry)
    $link.TargetPath = Join-Path ([Environment]::GetFolderPath('System')) 'WindowsPowerShell\v1.0\powershell.exe'
    $launcher = Join-Path $homeDir 'showcase.ps1'
    $link.Arguments = "-NoProfile -File `"$launcher`" -Action Start -Role $Role -Name $Name"
    $link.WorkingDirectory = $homeDir
    if ($Role -ne 'Backend') { $link.WindowStyle = 7 }
    $link.Save()
}

function Install-Launcher {
    $localLauncher = Join-Path $homeDir 'showcase.ps1'
    if ($PSCommandPath -ne $localLauncher) { Copy-Item -LiteralPath $PSCommandPath -Destination $localLauncher }
    New-LaunchShortcut
}

function Save-NewConfiguration($Config) {
    $temp = Join-Path $homeDir ([guid]::NewGuid().ToString() + '.tmp')
    try {
        $Config | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $temp -Encoding UTF8
        [IO.File]::Move($temp, $configuration)
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp }
    }
}

Assert-LocalPrivatePath $homeDir
if ($Action -eq 'Install') {
    if (Test-Path -LiteralPath $configuration) {
        Install-Launcher
        Write-Output 'This installation already exists. Its credentials and settings were preserved.'
        return
    }
    if ((Read-Host 'Type SHOWCASE to install for a dedicated demo environment') -cne 'SHOWCASE') {
        throw 'Showcase installation was cancelled.'
    }
    Protect-Directory $installRoot
    Protect-Directory $homeDir
    $config = [ordered]@{ version = 1; role = $Role; java = (Read-FilePath 'Java 21 java.exe full path') }
    if ($Role -eq 'Backend') {
        $config.jar = Read-FilePath 'Packaged Spring Boot server JAR full path'
        $config.keyStore = Read-FilePath 'Private PKCS12 key store full path'
        Assert-LocalPrivatePath $config.keyStore
        $config.keyStorePassword = ConvertFrom-SecureString (Read-Secret 'Private key store password')
        $config.listen = Read-Host 'Backend listen address or 127.0.0.1 for this PC only'
        if (!$config.listen) { throw 'A listen address is required.' }
        $port = Read-Host 'Backend HTTPS port'
        if ($port -notmatch '^\d{1,5}$' -or [int]$port -lt 1 -or [int]$port -gt 65535) { throw 'Invalid HTTPS port.' }
        $config.port = [int]$port
        $compose = Read-Host 'Existing compose.yaml full path, or Enter for a dedicated PostgreSQL instance you manage'
        if ($compose) {
            if (!(Test-Path -LiteralPath $compose -PathType Leaf)) { throw 'Choose the existing Compose file.' }
            $config.compose = (Resolve-Path -LiteralPath $compose).Path
            $config.dbUrl = 'jdbc:postgresql://127.0.0.1:55434/rose_roots'
            $config.dbUser = 'rose_roots'
        } else {
            $config.dbUrl = Read-Host 'JDBC URL of the dedicated showcase database'
            $config.dbUser = Read-Host 'Showcase database username'
            if ($config.dbUrl -notmatch '^jdbc:postgresql://' -or !$config.dbUser) {
                throw 'Supply the dedicated PostgreSQL URL and username.'
            }
        }
        $dbPassword = Read-Secret 'Showcase database password'
        $config.dbPassword = ConvertFrom-SecureString $dbPassword
        $config.bootstrapUser = Read-Host 'Existing bootstrap Manager username, or Enter if a persistent Manager already exists'
        if ($config.bootstrapUser) {
            $config.bootstrapHash = Protect-BootstrapCredential $config.java $config.jar
        }
        if ($config.compose) {
            $dbFile = Join-Path $homeDir 'compose-db-password'
            if (Test-Path -LiteralPath $dbFile) {
                if ([IO.File]::ReadAllText($dbFile) -cne (Reveal $dbPassword)) {
                    throw 'Keep the original Compose database password for this installation.'
                }
            } else {
                [IO.File]::WriteAllText($dbFile, (Reveal $dbPassword))
            }
        }
    } else {
        $distribution = Read-Host 'Packaged JavaFX distribution directory'
        $config.modules = (Resolve-Path -LiteralPath (Join-Path $distribution 'lib')).Path
        $origin = Read-Host 'Trusted server HTTPS origin'
        $uri = [uri]$origin
        if (!$uri.IsAbsoluteUri -or $uri.Scheme -ne 'https' -or !$uri.Host -or
            $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment -or $uri.UserInfo) {
            throw 'Use an HTTPS origin without an API path.'
        }
        $config.origin = $uri.GetLeftPart([UriPartial]::Authority)
        $publicTrust = Read-Host 'Public-certificate-only Java trust store path, or Enter if Java already trusts the server'
        if ($publicTrust) {
            $config.trustStore = (Resolve-Path -LiteralPath $publicTrust).Path
            $config.trustPassword = ConvertFrom-SecureString (Read-Secret 'Password of the public-certificate-only trust store')
        }
        $config.device = Read-DeviceCredential
    }
    Save-NewConfiguration $config
    Install-Launcher
    Write-Output "Installed the Rose Roots Showcase - $Role - $Name Start Menu shortcut."
    return
}

if (!(Test-Path -LiteralPath $configuration -PathType Leaf)) { throw 'Install this showcase role first.' }
$config = Get-Content -Raw -LiteralPath $configuration | ConvertFrom-Json
if ($config.version -ne 1 -or $config.role -ne $Role) { throw 'Invalid showcase installation configuration.' }
if ($Action -eq 'ReplaceDeviceCredential') {
    if ($Role -eq 'Backend') { throw 'This action is only for a Customer or Kitchen device.' }
    $config.device = Read-DeviceCredential
    $temp = Join-Path $homeDir ([guid]::NewGuid().ToString() + '.tmp')
    try {
        $config | ConvertTo-Json | Set-Content -LiteralPath $temp -Encoding UTF8
        [IO.File]::Replace($temp, $configuration, $null)
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp }
    }
    return
}

function Unprotect([string]$Encrypted) { Reveal (ConvertTo-SecureString $Encrypted) }

if ($Role -eq 'Backend') {
    if ($config.compose) {
        $env:ROSE_DB_PORT = '55434'
        $env:ROSE_DB_CONTAINER = 'rose-roots-showcase-db'
        $env:ROSE_DB_PASSWORD_FILE = Join-Path $homeDir 'compose-db-password'
        & docker --context desktop-linux compose --project-name rose-roots-showcase -f $config.compose up -d --wait
        if ($LASTEXITCODE) { throw 'The dedicated showcase PostgreSQL service did not start.' }
    }
    $env:SPRING_PROFILES_ACTIVE = 'catalog,lan'
    $env:ROSE_DB_URL = $config.dbUrl
    $env:ROSE_DB_USERNAME = $config.dbUser
    $env:ROSE_DB_PASSWORD = Unprotect $config.dbPassword
    $env:ROSE_SERVER_KEY_STORE = ([uri]$config.keyStore).AbsoluteUri
    $env:ROSE_SERVER_KEY_STORE_PASSWORD = Unprotect $config.keyStorePassword
    $env:ROSE_SERVER_ADDRESS = $config.listen
    $env:ROSE_SERVER_PORT = [string]$config.port
    $env:ROSE_IMAGE_DIR = Join-Path $homeDir 'menu-images'
    $env:ROSE_LOGO_DIR = Join-Path $homeDir 'menu-images\branding'
    $env:ROSE_ADMIN_MANAGER_USERNAME = $config.bootstrapUser
    $env:ROSE_ADMIN_MANAGER_PASSWORD_HASH = if ($config.bootstrapHash) { Unprotect $config.bootstrapHash }
    & $config.java -jar $config.jar
} else {
    $javaOptions = @()
    if ($config.trustStore) {
        $javaOptions = @("-Djavax.net.ssl.trustStore=$($config.trustStore)", '-Djavax.net.ssl.trustStoreType=PKCS12',
            "-Djavax.net.ssl.trustStorePassword=$(Unprotect $config.trustPassword)")
    }
    if ($Role -eq 'Customer') {
        $env:ROSE_ROOTS_BACKEND_URL = $config.origin
        $env:ROSE_ROOTS_INSTALLED_TERMINAL_TOKEN = Unprotect $config.device
        & $config.java @javaOptions --module-path $config.modules -m com.roseroots.customer/com.roseroots.customer.app.CustomerApplication --connected
    } else {
        $env:ROSE_BACKEND_URL = $config.origin
        $env:ROSE_ADMIN_SERVER_URL = $config.origin
        $env:ROSE_KITCHEN_MODE = 'connected'
        $env:ROSE_KITCHEN_INSTALLED_WORKSTATION_TOKEN = Unprotect $config.device
        & $config.java @javaOptions --module-path $config.modules -m com.roseroots.kitchen/com.roseroots.kitchen.app.KitchenApplication
    }
}
exit $LASTEXITCODE
