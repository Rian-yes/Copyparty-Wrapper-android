@echo off
setlocal

:: Set up specialized localized environments
set "JAVA_HOME=C:\Users\Share\AppData\Local\Programs\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
set "ANDROID_HOME=C:\Android\sdk"
set "PATH=%JAVA_HOME%\bin;%PATH%"

:: Ensure there is a task passed to the build file
if "%~1" == "" (
    echo [ERROR] No Gradle tasks specified.
    echo Usage: gradle.bat ^<tasks^> [options]
    echo Example: .\gradle.bat :app:assembleDebug
    exit /b 1
)

:: Execute the project's native wrapper archive with dynamic argument forwarding
"%JAVA_HOME%\bin\java.exe" -cp "gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %* -Pkotlin.compiler.execution.strategy=in-process

endlocal
