@echo off
call "%~dp0service.bat" stop
exit /b %ERRORLEVEL%
