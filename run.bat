@echo off
chcp 65001 >nul
cd /d %~dp0
echo ==========================================
echo   培训全流程管理系统 - 启动中...
echo ==========================================
if not exist out\com\training\Main.class (
  echo 首次运行，正在编译源代码...
  mkdir out 2>nul
  tools\jdk\bin\javac.exe -encoding UTF-8 -cp "lib/h2.jar" -d out src\com\training\*.java
  if errorlevel 1 (
    echo 编译失败，请检查 JDK 环境。
    pause
    exit /b 1
  )
)
echo 启动服务器，浏览器将自动打开 http://localhost:8080
echo 关闭本窗口即可停止系统。
tools\jdk\bin\java.exe -Dfile.encoding=UTF-8 -Dbootstrap.demo=true -Ddata.dir=demo-data -cp "out;lib/h2.jar" com.training.Main 8080
pause
