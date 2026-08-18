<#
.SYNOPSIS
    Builds the Windows installer for the Furniture Shop Inventory Management System
    (NFR-02, milestone M9): a self-contained MSI with a bundled Java runtime, so the shop
    PC needs no separate Java installation and no manual configuration beyond the
    first-run wizard.

.DESCRIPTION
    Three stages, each depending on the previous one's output:

    1. `mvn package` builds target/furniture-ims.jar - a Spring Boot executable fat jar
       (spring-boot-maven-plugin, configured in pom.xml) whose entry point is
       com.furnitureims.Launcher, not FurnitureImsApplication (which has no main()).

    2. jlink builds a trimmed custom Java runtime under target/runtime, containing only
       the JDK modules this app actually needs - NOT the JavaFX modules. JavaFX runs in
       classpath (non-modular) mode here, bundled inside the fat jar as regular
       dependency jars, the same way `mvn javafx:run` already runs it in development;
       jlink only needs to supply java.desktop and its supporting modules. The module
       list below was derived from `jdeps --recursive --print-module-deps` against the
       fat jar's extracted classpath, plus a few modules jdeps' static analysis cannot
       see because they are loaded reflectively or via a security-provider lookup rather
       than a direct class reference: jdk.crypto.ec (elliptic-curve TLS cipher suites -
       without it, HTTPS to accounts.google.com, login.microsoftonline.com/graph.microsoft.com
       (M12's OneDriveService) and most SMTP-over-TLS servers fails), java.net.http
       (OneDriveService's HttpClient - jdeps only sees this via a fat-jar-classpath scan,
       and it is easy to miss by hand since nothing else in this app used java.net.http
       before M12), java.naming, java.xml, java.logging, jdk.charsets and jdk.localedata. If a future
       dependency change needs a module not listed here, the app will fail fast at
       startup with a NoClassDefFoundError naming the missing class - re-run jdeps
       against the newly extracted jar (see the block below, commented out) and add
       whatever it reports.

    3. jpackage wraps that runtime image and the fat jar into a native Windows installer.
       `--main-jar` is passed WITHOUT `--main-class`: jpackage must read Main-Class from
       the jar's own manifest (org.springframework.boot.loader.launch.JarLauncher) so the
       native launcher runs the equivalent of `java -jar app\furniture-ims.jar` rather
       than trying to load com.furnitureims.Launcher directly off a plain classpath -
       that class lives inside BOOT-INF/classes/ in Spring Boot's fat-jar layout, which a
       direct -cp launch cannot see. (Getting this backwards produces a native launcher
       that always exits immediately with "Could not find or load main class".)

.PARAMETER InstallerType
    "msi" (default), "exe", or "app-image".

    msi and exe both produce a real Windows installer (Start Menu entry,
    uninstaller registered in "Add or Remove Programs") and both require the
    WiX Toolset (v3's light.exe/candle.exe, or v4/v5's wix.exe) on THIS build
    machine's PATH - a one-time developer-machine setup step, same category as
    the Google Cloud OAuth project setup docs/01-requirements.md section 5
    already documents as a prerequisite only the project owner can do. Install
    it from https://wixtoolset.org, or `choco install wixtoolset`. msi is the
    more common choice for enterprise/managed-PC deployment; exe is a
    self-extracting setup.exe some owners find more familiar to just
    double-click - functionally equivalent otherwise.

    app-image produces a runnable folder (with its own native .exe launcher
    and bundled runtime, no separate Java needed) with no installer step and
    no WiX dependency - useful for a quick local smoke test of the packaging
    pipeline, or for owners who'd rather just copy a folder than run an
    installer.

.EXAMPLE
    .\scripts\package-windows.ps1
    .\scripts\package-windows.ps1 -InstallerType exe
    .\scripts\package-windows.ps1 -InstallerType app-image
#>
param(
    [ValidateSet("msi", "exe", "app-image")]
    [string]$InstallerType = "msi",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME is not set - point it at a JDK 25 install (jlink/jpackage are invoked from `$env:JAVA_HOME\bin)."
}
$java = Join-Path $env:JAVA_HOME "bin\java.exe"
$jlink = Join-Path $env:JAVA_HOME "bin\jlink.exe"
$jpackage = Join-Path $env:JAVA_HOME "bin\jpackage.exe"

# ---- Stage 1: the executable fat jar --------------------------------------------------
if (-not $SkipBuild) {
    Write-Host "==> mvn clean package (skipping tests - run the full suite separately)"
    & mvn -q clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }
}

$fatJar = Join-Path $repoRoot "target\furniture-ims.jar"
if (-not (Test-Path $fatJar)) {
    throw "target\furniture-ims.jar not found - build it first (omit -SkipBuild)."
}

# Parsed directly from pom.xml rather than `mvn help:evaluate` - that goal needs a plugin
# this project's offline-capable build does not otherwise depend on, so on a machine
# whose local repo has never resolved it, it silently fails to reach Maven Central and
# the failure text (not a version string) ends up here instead. jpackage requires a
# plain numeric version (no "-SNAPSHOT" suffix).
[xml]$pomXml = Get-Content (Join-Path $repoRoot "pom.xml") -Raw
$appVersion = $pomXml.project.version -replace "-SNAPSHOT$", ""

# ---- Stage 2: the trimmed runtime image ------------------------------------------------
$runtimeDir = Join-Path $repoRoot "target\runtime"
if (Test-Path $runtimeDir) { Remove-Item $runtimeDir -Recurse -Force }

# Derived from `jdeps --multi-release 25 --print-module-deps --recursive --class-path
# "lib\*" furniture-ims.jar` run against the fat jar's extracted layers (`java
# -Djarmode=tools -jar furniture-ims.jar extract`), plus the reflective/SPI-loaded
# additions noted in the script header above.
$modules = @(
    "java.base", "java.compiler", "java.desktop", "java.instrument", "java.logging",
    "java.management", "java.naming", "java.net.http", "java.prefs", "java.scripting",
    "java.security.jgss", "java.sql", "java.sql.rowset", "java.xml",
    "jdk.charsets", "jdk.crypto.cryptoki", "jdk.crypto.ec", "jdk.httpserver", "jdk.jfr",
    "jdk.localedata", "jdk.unsupported"
) -join ","

Write-Host "==> jlink: building the trimmed runtime image"
& $jlink `
    --module-path "$env:JAVA_HOME\jmods" `
    --add-modules $modules `
    --output $runtimeDir `
    --no-header-files --no-man-pages --strip-debug --compress zip-6
if ($LASTEXITCODE -ne 0) { throw "jlink failed" }

# ---- Stage 3: the installer -------------------------------------------------------------
$jpackageInput = Join-Path $repoRoot "target\jpackage-input"
if (Test-Path $jpackageInput) { Remove-Item $jpackageInput -Recurse -Force }
New-Item -ItemType Directory -Path $jpackageInput | Out-Null
Copy-Item $fatJar $jpackageInput

$installerDest = Join-Path $repoRoot "target\installer"
if (Test-Path $installerDest) { Remove-Item $installerDest -Recurse -Force }

if ($InstallerType -ne "app-image") {
    $wixFound = (Get-Command "light.exe" -ErrorAction SilentlyContinue) `
        -or (Get-Command "wix.exe" -ErrorAction SilentlyContinue)
    if (-not $wixFound) {
        Write-Warning ("WiX Toolset not found on PATH - $InstallerType packaging will fail. Install it from " `
            + "https://wixtoolset.org (or `choco install wixtoolset`), or re-run with -InstallerType app-image.")
    }
}

Write-Host "==> jpackage: building the $InstallerType"
$jpackageArgs = @(
    "--type", $InstallerType,
    "--name", "Furniture Shop Inventory Management",
    "--app-version", $appVersion,
    "--vendor", "Furniture Shop",
    "--description", "Windows desktop inventory, GST billing and backup system for a furniture retail shop",
    "--input", $jpackageInput,
    "--main-jar", "furniture-ims.jar",
    "--runtime-image", $runtimeDir,
    "--dest", $installerDest
)
if ($InstallerType -ne "app-image") {
    # Installer-only options (MSI/EXE): a Start Menu entry, a desktop shortcut, and the
    # option to let the owner pick the install directory instead of a fixed default.
    # jpackage rejects all three for a plain app-image, which has no installer step to
    # attach them to.
    $jpackageArgs += @("--win-dir-chooser", "--win-menu", "--win-shortcut")
}
& $jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

Write-Host "==> Done: $installerDest"
Get-ChildItem $installerDest
