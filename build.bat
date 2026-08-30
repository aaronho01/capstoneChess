@echo off
setlocal enabledelayedexpansion

rem Compiles the engine and packages the runnable jars. Run "build" for every target or
rem "build <target>" for one of uci, gui, perft, tactical, book, all, or clean.

set "ROOT=%~dp0"
cd /d "%ROOT%"

set "RELEASE=25"
set "OUT=out"
set "UCI_JAR=capstone-chess-uci.jar"
set "GUI_JAR=CapstoneChess.jar"
set "UCI_MAIN=engine.forUCI.UciEngine"
set "GUI_MAIN=engine.engineDriver"
set "ENGINE_LIBS=lib\guava-33.4.0-jre.jar lib\failureaccess-1.0.2.jar"
set "CONT=  "

set "JDK_BIN="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jar.exe" set "JDK_BIN=%JAVA_HOME%\bin\"
if not defined JDK_BIN (
    for /f "delims=" %%P in ('where javac 2^>nul') do (
        if not defined JDK_BIN if exist "%%~dpPjar.exe" set "JDK_BIN=%%~dpP"
    )
)
if not defined JDK_BIN (
    for /d %%D in ("%ProgramFiles%\Java\jdk-*") do (
        if exist "%%~fD\bin\jar.exe" set "JDK_BIN=%%~fD\bin\"
    )
)
if not defined JDK_BIN (
    echo No JDK was found. Set JAVA_HOME to a JDK %RELEASE% installation and run this again.
    exit /b 1
)
echo Using the JDK at !JDK_BIN!

for %%J in (%ENGINE_LIBS%) do (
    if not exist "%%J" (
        echo Missing dependency: %%J
        exit /b 1
    )
)

set "ENGINE_CP="
for %%J in (%ENGINE_LIBS%) do (
    if defined ENGINE_CP (set "ENGINE_CP=!ENGINE_CP!;%%J") else (set "ENGINE_CP=%%J")
)

set "TARGET=%~1"
if not defined TARGET set "TARGET=all"

if /i "%TARGET%"=="all" goto :allTarget
if /i "%TARGET%"=="uci" goto :uciTarget
if /i "%TARGET%"=="gui" goto :guiTarget
if /i "%TARGET%"=="perft" goto :perftTarget
if /i "%TARGET%"=="tactical" goto :tacticalTarget
if /i "%TARGET%"=="book" goto :bookTarget
if /i "%TARGET%"=="match" goto :matchTarget
if /i "%TARGET%"=="clean" goto :cleanTarget
echo Unknown target: %TARGET%
goto :usage

:allTarget
call :buildUci || exit /b 1
call :buildGui || exit /b 1
call :buildSuite "perft" "engine.forTesting.PerftSuite" "src\engine\forTesting\PerftSuite.java" || exit /b 1
call :buildSuite "tactical" "engine.forTesting.TacticalSuite" "src\engine\forTesting\TacticalSuite.java" || exit /b 1
call :buildSuite "book" "engine.forTesting.OpeningBook" "src\engine\forTesting\OpeningBook.java" || exit /b 1
call :buildSuite "match" "engine.forTesting.SelfPlayMatch" "src\engine\forTesting\SelfPlayMatch.java" || exit /b 1
goto :done

:uciTarget
call :buildUci || exit /b 1
goto :done

:guiTarget
call :buildGui || exit /b 1
goto :done

:perftTarget
call :buildSuite "perft" "engine.forTesting.PerftSuite" "src\engine\forTesting\PerftSuite.java" || exit /b 1
goto :done

:bookTarget
call :buildSuite "book" "engine.forTesting.OpeningBook" "src\engine\forTesting\OpeningBook.java" || exit /b 1
goto :done

:matchTarget
call :buildSuite "match" "engine.forTesting.SelfPlayMatch" "src\engine\forTesting\SelfPlayMatch.java" || exit /b 1
goto :done

:cleanTarget
if exist "%OUT%" rmdir /s /q "%OUT%"
if exist "%UCI_JAR%" del /q "%UCI_JAR%"
if exist "%GUI_JAR%" del /q "%GUI_JAR%"
echo Removed the build output.
goto :done

:done
endlocal
exit /b 0

:usage
echo Usage: build [target]
echo.
echo   uci        compile the UCI engine and package %UCI_JAR%
echo   gui        compile the interface and package %GUI_JAR%
echo   perft      compile PerftSuite
echo   tactical   compile TacticalSuite
echo   book       compile OpeningBook
echo   match      compile SelfPlayMatch
echo   all        every target above, which is the default
echo   clean      remove %OUT% and both jars
exit /b 1

:compile
rem %1 is the output subdirectory, %2 is the entry point source file.
if exist "%OUT%\%~1" rmdir /s /q "%OUT%\%~1"
mkdir "%OUT%\%~1"
"%JDK_BIN%javac" --release %RELEASE% -nowarn -cp "lib\*" -sourcepath src -d "%OUT%\%~1" "%~2"
if errorlevel 1 (
    echo Compilation of %~2 failed. This build targets Java %RELEASE%, so javac must report that version or later.
    exit /b 1
)
exit /b 0

:buildUci
echo [uci] compiling
call :compile "uci" "src\engine\forUCI\UciEngine.java" || exit /b 1
echo [uci] unpacking dependencies
pushd "%OUT%\uci" || exit /b 1
for %%J in (%ENGINE_LIBS%) do (
    "%JDK_BIN%jar" xf "%ROOT%%%J"
    if errorlevel 1 (
        popd
        echo Unpacking %%J failed.
        exit /b 1
    )
)
popd
if exist "%OUT%\uci\META-INF" rmdir /s /q "%OUT%\uci\META-INF"
echo [uci] packaging %UCI_JAR%
"%JDK_BIN%jar" cfe "%UCI_JAR%" %UCI_MAIN% -C "%OUT%\uci" .
if errorlevel 1 exit /b 1
exit /b 0

:buildGui
echo [gui] compiling
call :compile "gui" "src\engine\engineDriver.java" || exit /b 1
echo [gui] writing the manifest
set "MANIFEST=%OUT%\manifest-gui.txt"
> "%MANIFEST%" echo Main-Class: %GUI_MAIN%
set "FIRST=1"
for %%J in (lib\*.jar) do (
    set "NAME=%%~nJ"
    set "SKIP="
    if "!NAME:~-8!"=="-sources" set "SKIP=1"
    if "!NAME:~-8!"=="-javadoc" set "SKIP=1"
    if not defined SKIP (
        if defined FIRST (
            >> "%MANIFEST%" echo Class-Path: lib/%%~nxJ
            set "FIRST="
        ) else (
            >> "%MANIFEST%" echo !CONT!lib/%%~nxJ
        )
    )
)
echo [gui] packaging %GUI_JAR%
"%JDK_BIN%jar" cfm "%GUI_JAR%" "%MANIFEST%" -C "%OUT%\gui" .
if errorlevel 1 exit /b 1
exit /b 0

:buildSuite
rem %1 is the output subdirectory, %2 is the main class, %3 is the entry point source file.
echo [%~1] compiling
call :compile "%~1" "%~3" || exit /b 1
echo [%~1] run from this directory with: java -cp "%OUT%\%~1;%ENGINE_CP%" %~2
exit /b 0