; AirGrab Windows installer.
;
; Compile with Inno Setup 6:  ISCC.exe AirGrab.iss
; Expects a finished PyInstaller build in ..\dist\AirGrab (tools\build_exe.py).
;
; The installer asks for admin because it adds the inbound firewall rule for
; TCP 53421 itself -- without that, first run would need "run as
; administrator" once, which nobody remembers to do.

#define AppName "AirGrab"
#define AppVersion "1.0"
#define DistDir "..\dist\AirGrab"

[Setup]
AppId={{7C1E4A72-3B60-4F1D-9C2B-04A11C4A8F01}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=AirGrab
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
PrivilegesRequired=admin
OutputDir=..\dist
OutputBaseFilename=AirGrab-Setup
SetupIconFile=..\assets\airgrab.ico
UninstallDisplayIcon={app}\AirGrab.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop icon"; GroupDescription: "Additional icons:"
Name: "startup"; Description: "Start AirGrab automatically when Windows starts"; GroupDescription: "Startup:"

[Files]
Source: "{#DistDir}\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\AirGrab.exe"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\AirGrab.exe"; Tasks: desktopicon
Name: "{userstartup}\{#AppName}"; Filename: "{app}\AirGrab.exe"; Tasks: startup

[Run]
; The app listens on TCP 53421 for incoming transfers; open it now, while we
; have admin, so the first real transfer is not silently blocked.
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""{#AppName}"""; Flags: runhidden; StatusMsg: "Configuring Windows Firewall..."
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""{#AppName}"" dir=in action=allow program=""{app}\AirGrab.exe"" enable=yes profile=private,domain"; Flags: runhidden; StatusMsg: "Configuring Windows Firewall..."
Filename: "{app}\AirGrab.exe"; Description: "Launch {#AppName} now"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""{#AppName}"""; Flags: runhidden; RunOnceId: "DelFirewallRule"
