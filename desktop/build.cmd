@echo off
rem Rebuilds FireRemote.exe from FireRemote.cs with the C# compiler that ships with Windows (.NET Framework 4.x).
set CSC=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe
if not exist "%CSC%" set CSC=%WINDIR%\Microsoft.NET\Framework\v4.0.30319\csc.exe
cd /d "%~dp0"
"%CSC%" /nologo /target:winexe /optimize+ /win32icon:FireRemote.ico /r:System.dll /r:System.Core.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll /out:FireRemote.exe FireRemote.cs ButtonsDialog.cs
if errorlevel 1 (
  echo Build failed.
  pause
  exit /b 1
)
echo Built FireRemote.exe
pause
