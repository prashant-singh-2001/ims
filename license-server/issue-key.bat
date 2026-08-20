@echo off
REM Double-click launcher for issue-key.sh - plain .sh files aren't
REM double-clickable on Windows by default, this runs it through Git Bash.
REM
REM Deliberately NOT a bare "bash" call: on a machine with WSL installed but
REM no distro configured, C:\Windows\System32\bash.exe (the WSL launcher
REM stub) can shadow Git Bash on PATH and fails instantly with no visible
REM error - which looks exactly like "the window opens and closes right
REM away". Pointing at Git Bash's own known install paths avoids that.
setlocal
set "SCRIPT_DIR=%~dp0"

if exist "C:\Program Files\Git\bin\bash.exe" (
    "C:\Program Files\Git\bin\bash.exe" "%SCRIPT_DIR%issue-key.sh"
    goto :done
)

if exist "C:\Program Files\Git\usr\bin\bash.exe" (
    "C:\Program Files\Git\usr\bin\bash.exe" "%SCRIPT_DIR%issue-key.sh"
    goto :done
)

echo Could not find Git Bash at its usual install location.
echo Install Git for Windows (https://git-scm.com/download/win), or edit this
echo .bat file to point at wherever bash.exe actually is on this machine.
pause
goto :eof

:done
if errorlevel 1 (
    echo.
    echo The script exited with an error - see above.
    pause
)
