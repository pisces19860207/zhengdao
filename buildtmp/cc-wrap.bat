@echo off
set ENVLOG=C:\Users\guoli\AndroidStudioProjects\wt-ci1\buildtmp\cc-env.log
echo [WRAP] TMP=%TMP% TEMP=%TEMP% TMPDIR=%TMPDIR% CD=%CD%>> "%ENVLOG%"
"C:\Users\guoli\AppData\Local\Android\Sdk\ndk\27.2.12479018\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe" --target=aarch64-linux-android35 %* >> "%ENVLOG%" 2>&1
exit /b %ERRORLEVEL%
