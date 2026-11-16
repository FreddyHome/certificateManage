@echo off
call "%~dp0service.bat" status
exit /b %ERRORLEVEL%
