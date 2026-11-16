@echo off
call "%~dp0service.bat" restart
exit /b %ERRORLEVEL%
