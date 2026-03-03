@echo off
title ReceiptNotice Server
color 0A

echo ==================================================
echo        ReceiptNotice Server - One Click Start
echo ==================================================
echo.

:: Detect Python
echo [1/3] Detecting Python...
set PYTHON=

py --version >nul 2>&1
if %errorlevel% equ 0 set PYTHON=py
if "%PYTHON%"=="" (
    python --version >nul 2>&1
    if %errorlevel% equ 0 set PYTHON=python
)
if "%PYTHON%"=="" (
    python3 --version >nul 2>&1
    if %errorlevel% equ 0 set PYTHON=python3
)
if "%PYTHON%"=="" (
    echo [ERROR] Python not found!
    echo         Install Python 3.7+ from https://www.python.org/downloads/
    echo         Make sure to check "Add Python to PATH"
    echo.
    pause
    exit /b 1
)
echo Found: %PYTHON%
%PYTHON% --version
echo.

:: Install dependencies
echo [2/3] Checking dependencies...
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

:: Start server
echo [3/3] Starting server...
echo Config: %~dp0config.json
echo.

%PYTHON% "%~dp0server.py"

echo.
echo Server stopped.
pause
