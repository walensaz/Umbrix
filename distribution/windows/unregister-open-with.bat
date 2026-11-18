@echo off
setlocal
echo ============================================================
echo   Umbrix Workstation — Remove Explorer Context Menu
echo ============================================================
echo.
echo Removing "Open with Umbrix" entries from current user registry...

reg delete "HKCU\Software\Classes\*\shell\OpenWithUmbrix" /f >nul 2>&1
reg delete "HKCU\Software\Classes\Directory\shell\OpenWithUmbrix" /f >nul 2>&1
reg delete "HKCU\Software\Classes\Applications\Umbrix.exe" /f >nul 2>&1

echo.
echo [SUCCESS] "Open with Umbrix" has been removed from your context menu.
echo.
pause
