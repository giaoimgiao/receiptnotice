@echo off
chcp 65001 >nul
title 收款通知服务端
color 0A

echo ============================================
echo        收款通知服务端 - 一键启动
echo ============================================
echo.

:: 检测 Python (优先 python, 其次 py, 最后 python3)
echo [1/4] 检测 Python 环境...
set PYTHON=
where python >nul 2>&1
if %errorlevel% equ 0 (
    set PYTHON=python
    goto :python_found
)
where py >nul 2>&1
if %errorlevel% equ 0 (
    set PYTHON=py
    goto :python_found
)
where python3 >nul 2>&1
if %errorlevel% equ 0 (
    set PYTHON=python3
    goto :python_found
)
echo [错误] 未检测到 Python，请先安装 Python 3.7+
echo        下载地址: https://www.python.org/downloads/
echo        安装时务必勾选 "Add Python to PATH"
echo.
pause
exit /b 1

:python_found

%PYTHON% --version
echo.

:: 检测 pip
echo [2/4] 检测 pip...
%PYTHON% -m pip --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] pip 不可用，正在尝试安装...
    %PYTHON% -m ensurepip --default-pip
    if %errorlevel% neq 0 (
        echo [错误] pip 安装失败，请手动安装
        pause
        exit /b 1
    )
)
echo pip 已就绪
echo.

:: 安装依赖
echo [3/4] 检查并安装依赖...
%PYTHON% -c "import flask" >nul 2>&1
set FLASK_OK=%errorlevel%
%PYTHON% -c "from Crypto.Cipher import DES" >nul 2>&1
set CRYPTO_OK=%errorlevel%

if %FLASK_OK% neq 0 (
    echo 正在安装 flask...
    %PYTHON% -m pip install flask -q
)
if %CRYPTO_OK% neq 0 (
    echo 正在安装 pycryptodome...
    %PYTHON% -m pip install pycryptodome -q
)

%PYTHON% -c "import flask; from Crypto.Cipher import DES" >nul 2>&1
if %errorlevel% neq 0 (
    echo [错误] 依赖安装失败，尝试完整安装...
    %PYTHON% -m pip install -r "%~dp0requirements.txt"
    if %errorlevel% neq 0 (
        echo [错误] 依赖安装失败，请检查网络后重试
        pause
        exit /b 1
    )
)
echo 依赖已就绪
echo.

:: 读取配置
set PORT=5000
set SECRET=

if exist "%~dp0config.txt" (
    echo 检测到 config.txt，读取配置...
    for /f "usebackq tokens=1,* delims==" %%a in ("%~dp0config.txt") do (
        if "%%a"=="PORT" set PORT=%%b
        if "%%a"=="SECRET" set SECRET=%%b
    )
)

:: 启动服务
echo [4/4] 启动服务端...
echo ============================================
echo   监听端口: %PORT%
if "%SECRET%"=="" (
    echo   加密: 未启用
) else (
    echo   加密: 已启用
)
echo.
echo   Web 界面:  http://localhost:%PORT%
echo   推送接口:  http://你的IP:%PORT%/api/receive
echo   记录查询:  http://你的IP:%PORT%/api/records
echo ============================================
echo.
echo   按 Ctrl+C 停止服务
echo.

if "%SECRET%"=="" (
    %PYTHON% "%~dp0server.py" --port %PORT%
) else (
    %PYTHON% "%~dp0server.py" --port %PORT% --secret %SECRET%
)

pause
