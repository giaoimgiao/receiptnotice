@echo off
title Receipt Notice Server
color 0A

echo ============================================
echo        Receipt Notice Server - Start
echo ============================================
echo.

:: Detect Python
echo [1/4] Detecting Python...
set PYTHON=

py --version >nul 2>&1
if %errorlevel% equ 0 (
    set PYTHON=py
)
if "%PYTHON%"=="" (
    python --version >nul 2>&1
    if %errorlevel% equ 0 (
        set PYTHON=python
    )
)
if "%PYTHON%"=="" (
    python3 --version >nul 2>&1
    if %errorlevel% equ 0 (
        set PYTHON=python3
    )
)
if "%PYTHON%"=="" (
    echo [ERROR] Python not found! Install Python 3.7+
    echo         https://www.python.org/downloads/
    echo         Check "Add Python to PATH" when installing
    echo.
    pause
    exit /b 1
)

echo Found: %PYTHON%
%PYTHON% --version
echo.

:: Check pip
echo [2/4] Checking pip...
%PYTHON% -m pip --version >nul 2>&1
if %errorlevel% neq 0 (
    echo pip not available, trying to install...
    %PYTHON% -m ensurepip --default-pip
)
echo pip OK
echo.

:: Install dependencies
echo [3/4] Checking dependencies...
%PYTHON% -c "import flask" >nul 2>&1
if %errorlevel% neq 0 (
    echo Installing flask...
    %PYTHON% -m pip install flask
)
%PYTHON% -c "from Crypto.Cipher import DES" >nul 2>&1
if %errorlevel% neq 0 (
    echo Installing pycryptodome...
    %PYTHON% -m pip install pycryptodome
)
echo Dependencies OK
echo.

:: Read config
set PORT=5000
set SECRET=

if exist "%~dp0config.txt" (
    for /f "usebackq tokens=1,* delims==" %%a in ("%~dp0config.txt") do (
        if "%%a"=="PORT" set PORT=%%b
        if "%%a"=="SECRET" set SECRET=%%b
    )
)

:: Start server
echo [4/4] Starting server...
echo ============================================
echo   Port: %PORT%
if "%SECRET%"=="" (
    echo   Encrypt: OFF
) else (
    echo   Encrypt: ON
)
echo.
echo   Web UI:    http://localhost:%PORT%
echo   Push API:  http://YOUR_IP:%PORT%/api/receive
echo   Records:   http://YOUR_IP:%PORT%/api/records
echo ============================================
echo.
echo   Press Ctrl+C to stop
echo.

if "%SECRET%"=="" (
    %PYTHON% "%~dp0server.py" --port %PORT%
) else (
    %PYTHON% "%~dp0server.py" --port %PORT% --secret %SECRET%
)

echo.
echo Server stopped.
pause
