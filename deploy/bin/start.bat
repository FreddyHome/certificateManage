@echo off
call "%~dp0service.bat" start
exit /b %ERRORLEVEL%
