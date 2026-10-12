@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
set "APP_DIR=%CD%"
set "EXE_PATH=%APP_DIR%\Umbrix.exe"

if not exist "%EXE_PATH%" (
    echo [ERROR] Umbrix.exe was not found in: %APP_DIR%
    echo Please make sure this script is located in the same directory as Umbrix.exe.
    pause
    exit /b 1
)

echo ============================================================
echo   Umbrix Workstation — Windows Explorer Context Menu Setup
echo ============================================================
echo.
echo Registering "Open with Umbrix" for current user (no admin rights required)...

:: 1. Add top-level context menu for all files (*)
reg add "HKCU\Software\Classes\*\shell\OpenWithUmbrix" /ve /d "Open with Umbrix" /f >nul
reg add "HKCU\Software\Classes\*\shell\OpenWithUmbrix" /v "Icon" /d "\"%EXE_PATH%\"" /f >nul
reg add "HKCU\Software\Classes\*\shell\OpenWithUmbrix\command" /ve /d "\"%EXE_PATH%\" \"%%1\"" /f >nul

:: 2. Add top-level context menu for directories / folders
reg add "HKCU\Software\Classes\Directory\shell\OpenWithUmbrix" /ve /d "Open Folder in Umbrix" /f >nul
reg add "HKCU\Software\Classes\Directory\shell\OpenWithUmbrix" /v "Icon" /d "\"%EXE_PATH%\"" /f >nul
reg add "HKCU\Software\Classes\Directory\shell\OpenWithUmbrix\command" /ve /d "\"%EXE_PATH%\" \"%%1\"" /f >nul

:: 3. Register application in SupportedTypes for "Open with..." submenu
reg add "HKCU\Software\Classes\Applications\Umbrix.exe\shell\open\command" /ve /d "\"%EXE_PATH%\" \"%%1\"" /f >nul
reg add "HKCU\Software\Classes\Applications\Umbrix.exe" /v "FriendlyAppName" /d "Umbrix Workstation" /f >nul

for %%E in (.log .txt .out .err .trace .jsonl .gz) do (
    reg add "HKCU\Software\Classes\Applications\Umbrix.exe\SupportedTypes" /v "%%E" /t REG_SZ /d "" /f >nul
)

echo.
echo [SUCCESS] "Open with Umbrix" is now registered!
echo You can now right-click any file in Windows Explorer and select "Open with Umbrix".
echo.
pause
