@echo off
REM Zen-Lang v3 Build Script (Java 17 / Windows)
if exist out rmdir /s /q out
mkdir out
dir /s /b src\*.java > sources.txt
javac -d out @sources.txt
if errorlevel 1 ( del sources.txt & echo BUILD FAILED & exit /b 1 )
del sources.txt
echo BUILD SUCCESSFUL
echo.
echo Run:  java -cp out zenlang.Main demo.zen
