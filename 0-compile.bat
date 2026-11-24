@echo off
if not exist out mkdir out
javac -d out src\DawnServer.java src\DawnClient.java src\ClientHandler.java src\Protocol.java src\Board.java src\Theme.java src\MapPanel.java
if errorlevel 1 (echo COMPILE FAILED - is the JDK installed? ) else (echo Compiled OK. Now run 1-start-server.bat)
pause
