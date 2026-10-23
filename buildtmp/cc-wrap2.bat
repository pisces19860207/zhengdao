@echo off
set TMP=C:\Users\guoli\AndroidStudioProjects\wt-ci1\buildtmp\clangtmp
set TEMP=C:\Users\guoli\AndroidStudioProjects\wt-ci1\buildtmp\clangtmp
"C:\Users\guoli\AppData\Local\Android\Sdk\ndk\27.2.12479018\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe" --target=aarch64-linux-android35 %*
exit /b %ERRORLEVEL%
