@echo off
setlocal
if "%~1"=="" (
  echo Usage: build.bat path-to-game-lib.jar
  exit /b 2
)
set "game_jar=%~f1"
set "compiler_java=java"
if defined JAVA_HOME set "compiler_java=%JAVA_HOME%\bin\java.exe"
set "build_root=%~dp0"
if exist "%build_root%build\classes" rmdir /s /q "%build_root%build\classes"
mkdir "%build_root%build\classes"
if not exist "%build_root%dist" mkdir "%build_root%dist"
(for /r "%build_root%src" %%F in (*.java) do echo "%%F") > "%build_root%build\sources.txt"
"%compiler_java%" -m jdk.compiler/com.sun.tools.javac.Main --release 8 -encoding UTF-8 -cp "%game_jar%" -d "%build_root%build\classes" "@%build_root%build\sources.txt"
if errorlevel 1 exit /b 1
"%compiler_java%" -m jdk.jartool/sun.tools.jar.Main --create --file "%build_root%dist\rw-agent-bootstrap.jar" --manifest "%build_root%MANIFEST.MF" -C "%build_root%build\classes" . -C "%build_root%resources" .
if errorlevel 1 exit /b 1
echo Built: %build_root%dist\rw-agent-bootstrap.jar
