@echo off
setlocal
set APP_HOME=%~dp0
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
if not exist "%WRAPPER_JAR%" (
  echo gradle-wrapper.jar is missing from gradle\wrapper\.
  echo Fix: copy it from the official Forge 1.20.1 MDK, or run "gradle wrapper --gradle-version 8.1.1" once,
  echo or just open the project in IntelliJ IDEA ^(it downloads Gradle itself^).
  exit /b 1
)
if defined JAVA_HOME (set JAVACMD=%JAVA_HOME%\bin\java.exe) else (set JAVACMD=java.exe)
"%JAVACMD%" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
