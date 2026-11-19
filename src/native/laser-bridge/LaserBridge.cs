// LaserBridge - 32-bit Ezcad2 (MarkEzd.dll) JSON bridge process.
//
// Protocol: one JSON object per line on stdin (requests) / stdout (responses and events).
//   Request : {"id":1,"cmd":"init","params":{"sdkPath":"C:\\sdk","testMode":true}}
//   Request : {"id":1,"cmd":"design","params":{"design":{"name":"...","widthMm":100,"heightMm":60,
//             "pen":0,"objects":[{"kind":"text","name":"t1","text":"...","x":1,"y":2,"width":30,
//             "height":5,"rotation":0,"pen":0,"attributes":{...}},...]}}}
//   Response: {"id":1,"ok":true,"result":{...}}  or  {"id":1,"ok":false,"error":{"code":"...","message":"...","sdkCode":3}}
//   Event   : {"event":"markFinished","data":{"code":0}}
//
// Commands: ping, init, load, entities, change, design, mark, redLight, stop, close.
// The process is compiled x86 so it can load the PE32 MarkEzd.dll; a 64-bit JVM
// cannot load that DLL directly, hence this bridge.
//
// Build (see build-laser-bridge.ps1):
//   C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe /platform:x86 /r:System.Web.Extensions.dll LaserBridge.cs
//
// SDK facts (verified against Ezcad2开发人员手册.chm and MarkEzdDll.h):
//   - TCHAR APIs: UNICODE build => wchar_t strings, cdecl calling convention.
//   - lmc1_Mark blocks until processing ends; lmc1_StopMark must run on another thread.
//   - Almost every function returns a common int error code (0 == success).

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

namespace LaserBridge
{
    internal static class Ezd
    {
        public const int LMC1_ERR_SUCCESS = 0;
        public const int LMC1_ERR_EZCADRUN = 1;
        public const int LMC1_ERR_NOFINDCFGFILE = 2;
        public const int LMC1_ERR_FAILEDOPEN = 3;
        public const int LMC1_ERR_NODEVICE = 4;
        public const int LMC1_ERR_HARDVER = 5;
        public const int LMC1_ERR_DEVCFG = 6;
        public const int LMC1_ERR_STOPSIGNAL = 7;
        public const int LMC1_ERR_USERSTOP = 8;
        public const int LMC1_ERR_UNKNOW = 9;
        public const int LMC1_ERR_OUTTIME = 10;
        public const int LMC1_ERR_NOINITIAL = 11;
        public const int LMC1_ERR_READFILE = 12;
        public const int LMC1_ERR_OWENWNDNULL = 13;
        public const int LMC1_ERR_NOFINDFONT = 14;
        public const int LMC1_ERR_PENNO = 15;
        public const int LMC1_ERR_NOTTEXT = 16;
        public const int LMC1_ERR_SAVEFILE = 17;
        public const int LMC1_ERR_NOFINDENT = 18;
        public const int LMC1_ERR_STATUE = 19;
        public const int LMC1_ERR_PARAM = 20;
        public const int LMC1_ERR_DEVICE = 21;

        public static string Message(int code)
        {
            switch (code)
            {
                case LMC1_ERR_SUCCESS: return "success";
                case LMC1_ERR_EZCADRUN: return "EZCAD is already running";
                case LMC1_ERR_NOFINDCFGFILE: return "EZCAD.CFG not found";
                case LMC1_ERR_FAILEDOPEN: return "failed to open LMC1";
                case LMC1_ERR_NODEVICE: return "no valid lmc1 device";
                case LMC1_ERR_HARDVER: return "lmc1 version mismatch";
                case LMC1_ERR_DEVCFG: return "device configuration file not found";
                case LMC1_ERR_STOPSIGNAL: return "alarm signal";
                case LMC1_ERR_USERSTOP: return "stopped by user";
                case LMC1_ERR_UNKNOW: return "unknown error";
                case LMC1_ERR_OUTTIME: return "timeout";
                case LMC1_ERR_NOINITIAL: return "not initialized";
                case LMC1_ERR_READFILE: return "file read error";
                case LMC1_ERR_OWENWNDNULL: return "owner window is null";
                case LMC1_ERR_NOFINDFONT: return "font not found";
                case LMC1_ERR_PENNO: return "invalid pen number";
                case LMC1_ERR_NOTTEXT: return "entity is not a text object";
                case LMC1_ERR_SAVEFILE: return "failed to save file";
                case LMC1_ERR_NOFINDENT: return "entity not found";
                case LMC1_ERR_STATUE: return "operation not allowed in current state";
                case LMC1_ERR_PARAM: return "invalid parameter";
                case LMC1_ERR_DEVICE: return "hardware error";
                default: return "unrecognized error code " + code;
            }
        }

        [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)]
        public static extern IntPtr LoadLibrary(string lpFileName);

        [DllImport("kernel32", CharSet = CharSet.Ansi, SetLastError = true)]
        public static extern IntPtr GetProcAddress(IntPtr hModule, string procName);

        [DllImport("kernel32", CharSet = CharSet.Unicode)]
        public static extern IntPtr GetModuleHandle(string moduleName);

        [DllImport("kernel32", CharSet = CharSet.Unicode)]
        public static extern uint GetModuleFileName(IntPtr module, StringBuilder path, int size);

        // Pre-load MarkEzd.dll by absolute path so the DllImport bindings below
        // resolve against the already-loaded module regardless of the working directory.
        // MarkEzd's DllMain locates EZCAD.CFG / FONT / PARAM relative to the process
        // current directory (verified empirically and per the manual: runtime files must
        // sit next to the host program), so the current directory must be the SDK path.
        public static IntPtr LoadSdk(string sdkPath)
        {
            string dllPath = Path.Combine(sdkPath, "MarkEzd.dll");
            IntPtr handle = LoadLibrary(dllPath);
            if (handle == IntPtr.Zero)
            {
                throw new InvalidOperationException("LoadLibrary failed for " + dllPath +
                    " (win32 error " + Marshal.GetLastWin32Error() + ")." +
                    " The SDK directory must contain the full Ezcad2 runtime (MarkEzd.dll," +
                    " EZCAD.CFG, FONT, PARAM) and the process working directory must be that directory.");
            }
            return handle;
        }

        public static T Bind<T>(IntPtr module, string name) where T : class
        {
            IntPtr p = GetProcAddress(module, name);
            if (p == IntPtr.Zero) return null;
            return Marshal.GetDelegateForFunctionPointer(p, typeof(T)) as T;
        }
    }

    // UNICODE TCHAR* -> LPWStr, cdecl (MSVC default).
    internal delegate int Lmc1Initial2([MarshalAs(UnmanagedType.LPWStr)] string strEzCadPath,
        [MarshalAs(UnmanagedType.Bool)] bool bTestMode);
    internal delegate int Lmc1Initial([MarshalAs(UnmanagedType.LPWStr)] string strEzCadPath,
        [MarshalAs(UnmanagedType.Bool)] bool bTestMode, IntPtr hOwenWnd);
    internal delegate int Lmc1Close();
    internal delegate int Lmc1LoadEzdFile([MarshalAs(UnmanagedType.LPWStr)] string strFileName);
    internal delegate int Lmc1GetEntityCount();
    internal delegate int Lmc1GetEntityName(int nEntityIndex,
        [MarshalAs(UnmanagedType.LPWStr)] StringBuilder szEntName);
    internal delegate int Lmc1GetTextByName([MarshalAs(UnmanagedType.LPWStr)] string strTextName,
        [MarshalAs(UnmanagedType.LPWStr)] StringBuilder strText);
    internal delegate int Lmc1ChangeTextByName([MarshalAs(UnmanagedType.LPWStr)] string strTextName,
        [MarshalAs(UnmanagedType.LPWStr)] string strTextNew);
    internal delegate int Lmc1Mark([MarshalAs(UnmanagedType.Bool)] bool bFlyMark);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    internal delegate int Lmc1RedLightMark();
    internal delegate int Lmc1StopMark();
    internal delegate int Lmc1IsMarking();

    // Design (entity library) APIs, per MarkEzdDll.h: UNICODE TCHAR*, cdecl.
    // BOOL parameters marshal as 4-byte UnmanagedType.Bool; the C++ bool pair in
    // lmc1_SetTextEntParam4 marshals as 1-byte UnmanagedType.I1.
    [UnmanagedFunctionPointer(CallingConvention.Cdecl, CharSet = CharSet.Unicode)]
    internal delegate int Lmc1ClearEntLib();
    [UnmanagedFunctionPointer(CallingConvention.Cdecl, CharSet = CharSet.Unicode)]
    internal delegate int Lmc1AddTextToLib([MarshalAs(UnmanagedType.LPWStr)] string pStr,
        [MarshalAs(UnmanagedType.LPWStr)] string pEntName,
        double dPosX, double dPosY, double dPosZ, int nAlign, double dTextRotateAngle,
        int nPenNo, [MarshalAs(UnmanagedType.Bool)] bool bHatchText);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl, CharSet = CharSet.Unicode)]
    internal delegate int Lmc1SetTextEntParam4([MarshalAs(UnmanagedType.LPWStr)] string strTextName,
        [MarshalAs(UnmanagedType.LPWStr)] string strFontName,
        int nTextSpaceMode, double dTextSpace, double dCharHeight, double charWidthRatio,
        double dCharAngle, double dCharSpace, double dLineSpace, double spaceWidthRatio,
        int nTextAlign,
        [MarshalAs(UnmanagedType.I1)] bool bBold, [MarshalAs(UnmanagedType.I1)] bool bItalic);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl, CharSet = CharSet.Unicode)]
    internal delegate int Lmc1AddBarCodeToLib([MarshalAs(UnmanagedType.LPWStr)] string pStr,
        [MarshalAs(UnmanagedType.LPWStr)] string pEntName,
        double dPosX, double dPosY, double dPosZ, int nAlign, int nPenNo, int bHatchText,
        int nBarcodeType, [MarshalAs(UnmanagedType.U2)] ushort wBarCodeAttrib,
        double dHeight, double dNarrowWidth,
        [MarshalAs(UnmanagedType.LPArray, SizeConst = 4)] double[] dBarWidthScale,
        [MarshalAs(UnmanagedType.LPArray, SizeConst = 4)] double[] dSpaceWidthScale,
        double dMidCharSpaceScale,
        double dQuietLeftScale, double dQuietMidScale, double dQuietRightScale,
        double dQuietTopScale, double dQuietBottomScale,
        int nRow, int nCol, int nCheckLevel, int nSizeMode,
        double dTextHeight, double dTextWidth, double dTextOffsetX, double dTextOffsetY,
        double dTextSpace, double dDiameter,
        [MarshalAs(UnmanagedType.LPWStr)] string pTextFontName);
    // Flat contiguous (x1,y1,x2,y2,...) buffer; cdecl passes a pointer to the first
    // element, matching the header's double ptBuf[][2] (decayed to double*).
    [UnmanagedFunctionPointer(CallingConvention.Cdecl, CharSet = CharSet.Unicode)]
    internal delegate int Lmc1AddCurveToLib([MarshalAs(UnmanagedType.LPArray)] double[] ptBuf,
        int ptNum, [MarshalAs(UnmanagedType.LPWStr)] string pEntName,
        int nPenNo, int bHatch);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl, CharSet = CharSet.Unicode)]
    internal delegate int Lmc1AddFileToLib([MarshalAs(UnmanagedType.LPWStr)] string pFileName,
        [MarshalAs(UnmanagedType.LPWStr)] string pEntName,
        double dPosX, double dPosY, double dPosZ, int nAlign, double dRatio, int nPenNo,
        [MarshalAs(UnmanagedType.Bool)] bool bHatchFile);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl, CharSet = CharSet.Unicode)]
    internal delegate int Lmc1GetEntSize([MarshalAs(UnmanagedType.LPWStr)] string pEntName,
        out double dMinx, out double dMiny, out double dMaxx, out double dMaxy, out double dZ);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl, CharSet = CharSet.Unicode)]
    internal delegate int Lmc1ScaleEnt([MarshalAs(UnmanagedType.LPWStr)] string pEntName,
        double dCenx, double dCeny, double dScaleX, double dScaleY);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl, CharSet = CharSet.Unicode)]
    internal delegate int Lmc1MoveEnt([MarshalAs(UnmanagedType.LPWStr)] string pEntName,
        double dMovex, double dMovey);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl, CharSet = CharSet.Unicode)]
    internal delegate int Lmc1RotateEnt([MarshalAs(UnmanagedType.LPWStr)] string pEntName,
        double dCenx, double dCeny, double dAngle);

    internal sealed class Program
    {
        private static readonly JavaScriptSerializer Json = new JavaScriptSerializer();
        private static readonly object OutLock = new object();
        private static readonly object StateLock = new object();

        private static IntPtr s_module;
        private static bool s_initialized;
        private static bool s_closed;
        private static bool s_initHung;
        private static bool s_marking;
        private static int s_markGeneration;

        private static Lmc1Initial2 s_initial2;
        private static Lmc1Initial s_initial;
        private static Lmc1Close s_close;
        private static Lmc1LoadEzdFile s_loadEzd;
        private static Lmc1GetEntityCount s_getEntityCount;
        private static Lmc1GetEntityName s_getEntityName;
        private static Lmc1GetTextByName s_getTextByName;
        private static Lmc1ChangeTextByName s_changeTextByName;
        private static Lmc1Mark s_mark;
        private static Lmc1RedLightMark s_redLightMark;
        private static Lmc1StopMark s_stopMark;
        private static Lmc1ClearEntLib s_clearEntLib;
        private static Lmc1AddTextToLib s_addTextToLib;
        private static Lmc1SetTextEntParam4 s_setTextEntParam4;
        private static Lmc1AddBarCodeToLib s_addBarCodeToLib;
        private static Lmc1AddCurveToLib s_addCurveToLib;
        private static Lmc1AddFileToLib s_addFileToLib;
        private static Lmc1GetEntSize s_getEntSize;
        private static Lmc1ScaleEnt s_scaleEnt;
        private static Lmc1MoveEnt s_moveEnt;
        private static Lmc1RotateEnt s_rotateEnt;

        private static void WriteLine(string line)
        {
            lock (OutLock)
            {
                Console.Out.Write(line);
                Console.Out.Write('\n');
                Console.Out.Flush();
            }
        }

        private static void RespondOk(object id, Dictionary<string, object> result)
        {
            WriteLine(Json.Serialize(new Dictionary<string, object>
            {
                {"id", id}, {"ok", true}, {"result", result ?? new Dictionary<string, object>()}
            }));
        }

        private static void RespondError(object id, string code, string message, int? sdkCode)
        {
            var err = new Dictionary<string, object> {{"code", code}, {"message", message}};
            if (sdkCode.HasValue) err["sdkCode"] = sdkCode.Value;
            WriteLine(Json.Serialize(new Dictionary<string, object>
            {
                {"id", id}, {"ok", false}, {"error", err}
            }));
        }

        private static void EmitEvent(string name, Dictionary<string, object> data)
        {
            WriteLine(Json.Serialize(new Dictionary<string, object>
            {
                {"event", name}, {"data", data}
            }));
        }

        private static void InitDiagnostic(string message)
        {
            EmitEvent("initDiagnostic", new Dictionary<string, object> {{"message", message}});
        }

        private static string DescribeModule(string name, IntPtr module)
        {
            if (module == IntPtr.Zero) return name + " not loaded";
            var path = new StringBuilder(32768);
            uint length = Ezd.GetModuleFileName(module, path, path.Capacity);
            if (length == 0 || length >= path.Capacity) return name + " loaded, path unavailable";
            string filename = path.ToString();
            try
            {
                var version = FileVersionInfo.GetVersionInfo(filename);
                var file = new FileInfo(filename);
                return name + " loaded from " + filename + ", fileVersion=" +
                    (version.FileVersion ?? "unavailable") + ", productVersion=" +
                    (version.ProductVersion ?? "unavailable") + ", bytes=" + file.Length +
                    ", modifiedUtc=" + file.LastWriteTimeUtc.ToString("s", CultureInfo.InvariantCulture) + "Z";
            }
            catch (Exception ex)
            {
                return name + " loaded from " + filename + ", metadata unavailable: " + ex.Message;
            }
        }

        private static int Main()
        {
            Console.InputEncoding = Encoding.UTF8;
            Console.OutputEncoding = new UTF8Encoding(false); // UTF-8, no BOM
            Json.MaxJsonLength = 16 * 1024 * 1024;

            string line;
            while ((line = Console.In.ReadLine()) != null)
            {
                if (line.Length == 0) continue;
                Dictionary<string, object> request;
                try
                {
                    request = Json.Deserialize<Dictionary<string, object>>(line);
                }
                catch (Exception ex)
                {
                    RespondError(null, "BAD_REQUEST", "invalid JSON request: " + ex.Message, null);
                    continue;
                }
                object id = request.ContainsKey("id") ? request["id"] : null;
                string cmd = request.ContainsKey("cmd") ? request["cmd"] as string : null;
                var ps = request.ContainsKey("params") && request["params"] is Dictionary<string, object>
                    ? (Dictionary<string, object>)request["params"] : new Dictionary<string, object>();

                try
                {
                    Dispatch(id, cmd, ps);
                }
                catch (Exception ex)
                {
                    RespondError(id, "INTERNAL", ex.GetType().Name + ": " + ex.Message, null);
                }

                if (s_closed) break;
            }

            ShutdownSdk();
            return 0;
        }

        private static void Dispatch(object id, string cmd, Dictionary<string, object> ps)
        {
            switch (cmd == null ? "" : cmd)
            {
                case "ping":
                    RespondOk(id, new Dictionary<string, object>
                    {
                        {"pong", true},
                        {"arch", Environment.Is64BitProcess ? "x64" : "x86"},
                        {"initialized", s_initialized},
                        {"initHung", s_initHung}
                    });
                    return;
                case "init":
                    CmdInit(id, ps);
                    return;
                case "load":
                    CmdLoad(id, ps);
                    return;
                case "entities":
                    CmdEntities(id);
                    return;
                case "change":
                    CmdChange(id, ps);
                    return;
                case "design":
                    CmdDesign(id, ps);
                    return;
                case "mark":
                    CmdMark(id, ps);
                    return;
                case "redLight":
                    CmdRedLight(id);
                    return;
                case "stop":
                    CmdStop(id);
                    return;
                case "close":
                    CmdClose(id);
                    return;
                default:
                    RespondError(id, "UNKNOWN_COMMAND", "unknown command: " + cmd, null);
                    return;
            }
        }

        private static string GetString(Dictionary<string, object> ps, string key)
        {
            object v;
            if (!ps.TryGetValue(key, out v) || v == null) return null;
            return v as string;
        }

        private static bool GetBool(Dictionary<string, object> ps, string key, bool def)
        {
            object v;
            if (!ps.TryGetValue(key, out v) || v == null) return def;
            if (v is bool) return (bool)v;
            return def;
        }

        private static void CmdInit(object id, Dictionary<string, object> ps)
        {
            string sdkPath = GetString(ps, "sdkPath");
            bool testMode = GetBool(ps, "testMode", true);

            lock (StateLock)
            {
                if (s_initialized) { RespondError(id, "ALREADY_INITIALIZED", "SDK already initialized; close first", null); return; }
                if (s_initHung) { RespondError(id, "INIT_HUNG", "SDK initialization is stuck; only close is available", null); return; }
                if (s_closed) { RespondError(id, "CLOSED", "bridge already closed", null); return; }
            }
            if (string.IsNullOrWhiteSpace(sdkPath))
            {
                RespondError(id, "INVALID_PATH", "sdkPath is required", null);
                return;
            }
            if (!Directory.Exists(sdkPath))
            {
                RespondError(id, "INVALID_PATH", "sdkPath does not exist: " + sdkPath, null);
                return;
            }
            if (!File.Exists(Path.Combine(sdkPath, "MarkEzd.dll")))
            {
                RespondError(id, "INVALID_PATH", "MarkEzd.dll not found under sdkPath: " + sdkPath, null);
                return;
            }

            try
            {
                // The SDK DllMain resolves EZCAD.CFG/FONT/PARAM against the process
                // current directory, so force it before the DLL is mapped.
                Environment.CurrentDirectory = sdkPath;
                InitDiagnostic("LoadLibrary " + Path.Combine(sdkPath, "MarkEzd.dll"));
                s_module = Ezd.LoadSdk(sdkPath);
                InitDiagnostic(DescribeModule("MarkEzd.dll", s_module));
            }
            catch (Exception ex)
            {
                RespondError(id, "DLL_LOAD_FAILED", ex.Message, null);
                return;
            }

            s_initial2 = Ezd.Bind<Lmc1Initial2>(s_module, "lmc1_Initial2");
            s_initial = Ezd.Bind<Lmc1Initial>(s_module, "lmc1_Initial");
            InitDiagnostic("exports: lmc1_Initial2=" + (s_initial2 != null) +
                ", lmc1_Initial=" + (s_initial != null));
            s_close = Ezd.Bind<Lmc1Close>(s_module, "lmc1_Close");
            s_loadEzd = Ezd.Bind<Lmc1LoadEzdFile>(s_module, "lmc1_LoadEzdFile");
            s_getEntityCount = Ezd.Bind<Lmc1GetEntityCount>(s_module, "lmc1_GetEntityCount");
            s_getEntityName = Ezd.Bind<Lmc1GetEntityName>(s_module, "lmc1_GetEntityName");
            s_getTextByName = Ezd.Bind<Lmc1GetTextByName>(s_module, "lmc1_GetTextByName");
            s_changeTextByName = Ezd.Bind<Lmc1ChangeTextByName>(s_module, "lmc1_ChangeTextByName");
            s_mark = Ezd.Bind<Lmc1Mark>(s_module, "lmc1_Mark");
            s_redLightMark = Ezd.Bind<Lmc1RedLightMark>(s_module, "lmc1_RedLightMark");
            s_stopMark = Ezd.Bind<Lmc1StopMark>(s_module, "lmc1_StopMark");

            // Design (entity library) exports are bound best-effort: SDK builds that
            // predate them must not fail init for the classic commands; CmdDesign
            // reports DLL_BIND_FAILED itself when any is missing.
            s_clearEntLib = Ezd.Bind<Lmc1ClearEntLib>(s_module, "lmc1_ClearEntLib");
            s_addTextToLib = Ezd.Bind<Lmc1AddTextToLib>(s_module, "lmc1_AddTextToLib");
            s_setTextEntParam4 = Ezd.Bind<Lmc1SetTextEntParam4>(s_module, "lmc1_SetTextEntParam4");
            s_addBarCodeToLib = Ezd.Bind<Lmc1AddBarCodeToLib>(s_module, "lmc1_AddBarCodeToLib");
            s_addCurveToLib = Ezd.Bind<Lmc1AddCurveToLib>(s_module, "lmc1_AddCurveToLib");
            s_addFileToLib = Ezd.Bind<Lmc1AddFileToLib>(s_module, "lmc1_AddFileToLib");
            s_getEntSize = Ezd.Bind<Lmc1GetEntSize>(s_module, "lmc1_GetEntSize");
            s_scaleEnt = Ezd.Bind<Lmc1ScaleEnt>(s_module, "lmc1_ScaleEnt");
            s_moveEnt = Ezd.Bind<Lmc1MoveEnt>(s_module, "lmc1_MoveEnt");
            s_rotateEnt = Ezd.Bind<Lmc1RotateEnt>(s_module, "lmc1_RotateEnt");

            if (s_close == null || s_loadEzd == null || s_getEntityCount == null ||
                s_getEntityName == null || s_getTextByName == null || s_changeTextByName == null ||
                s_mark == null || s_stopMark == null ||
                (s_initial == null && s_initial2 == null))
            {
                RespondError(id, "DLL_BIND_FAILED", "MarkEzd.dll is missing required exports", null);
                return;
            }

            // lmc1_Initial can block indefinitely when the LMC1 driver/card is absent
            // (observed on a driverless machine: the call never returns). Run it on a
            // dedicated thread and report a timeout instead of stalling the protocol;
            // after a hang the bridge stays alive only so the client can close/exit.
            int timeoutMs;
            {
                object tv;
                if (!ps.TryGetValue("initTimeoutMs", out tv) || tv == null) timeoutMs = 20000;
                else if (!int.TryParse(tv.ToString(), out timeoutMs) || timeoutMs <= 0) timeoutMs = 20000;
            }
            var gate = new ManualResetEventSlim(false);
            var result = new int?[1];
            var failure = new Exception[1];
            string entry = s_initial2 != null ? "lmc1_Initial2" : "lmc1_Initial";
            InitDiagnostic("calling " + entry + ", testMode=" + testMode + ", timeoutMs=" + timeoutMs);
            var elapsed = Stopwatch.StartNew();
            var initThread = new Thread(() =>
            {
                try
                {
                    result[0] = s_initial2 != null ? s_initial2(sdkPath, testMode) : s_initial(sdkPath, testMode, IntPtr.Zero);
                }
                catch (Exception ex)
                {
                    failure[0] = ex;
                }
                gate.Set();
            });
            initThread.IsBackground = true;
            initThread.Start();
            if (!gate.Wait(timeoutMs))
            {
                InitDiagnostic(entry + " still waiting after " + elapsed.ElapsedMilliseconds + " ms");
                lock (StateLock) { s_initHung = true; }
                s_close = null; // never call lmc1_Close on a failed initialization
                RespondError(id, "INIT_TIMEOUT",
                    "lmc1_Initial did not return within " + timeoutMs +
                    "ms; the LMC1 driver/board is most likely missing or not responding", null);
                return;
            }
            if (failure[0] != null)
            {
                RespondError(id, "SDK_ERROR", "lmc1_Initial raised " + failure[0].GetType().Name + ": " + failure[0].Message, null);
                return;
            }
            int rc = result[0].Value;
            InitDiagnostic(entry + " returned " + rc + " (" + Ezd.Message(rc) + ") after " +
                elapsed.ElapsedMilliseconds + " ms; " +
                DescribeModule("Lmc1.dll", Ezd.GetModuleHandle("Lmc1.dll")));
            if (rc != Ezd.LMC1_ERR_SUCCESS)
            {
                // Do not unload the SDK here: reload after unload breaks subsequent
                // LoadLibrary calls (win32 error 126). Keep the DLL mapped; a later
                // close/exit releases it.
                s_close = null; // never call lmc1_Close on a failed initialization
                RespondError(id, "SDK_ERROR", "lmc1_Initial failed: " + Ezd.Message(rc), rc);
                return;
            }

            lock (StateLock) { s_initialized = true; }
            RespondOk(id, new Dictionary<string, object>
            {
                {"initialized", true},
                {"testMode", testMode},
                {"sdkPath", sdkPath},
                {"usedInitial2", s_initial2 != null}
            });
        }

        private static void EnsureReady(object id, out bool ok)
        {
            ok = false;
            lock (StateLock)
            {
                if (s_closed) { RespondError(id, "CLOSED", "bridge already closed", null); return; }
                if (s_initHung) { RespondError(id, "INIT_HUNG", "SDK initialization is stuck; only close is available", null); return; }
                if (!s_initialized) { RespondError(id, "NOT_INITIALIZED", "SDK not initialized", null); return; }
                if (s_marking) { RespondError(id, "BUSY_MARKING", "marking in progress; stop before this operation", null); return; }
            }
            ok = true;
        }

        private static void CmdLoad(object id, Dictionary<string, object> ps)
        {
            bool ok;
            EnsureReady(id, out ok);
            if (!ok) return;

            string templatePath = GetString(ps, "templatePath");
            if (string.IsNullOrWhiteSpace(templatePath))
            {
                RespondError(id, "INVALID_PATH", "templatePath is required", null);
                return;
            }
            if (!File.Exists(templatePath))
            {
                RespondError(id, "INVALID_PATH", "template file does not exist: " + templatePath, null);
                return;
            }

            int rc = s_loadEzd(templatePath);
            if (rc != Ezd.LMC1_ERR_SUCCESS)
            {
                RespondError(id, "SDK_ERROR", "lmc1_LoadEzdFile failed: " + Ezd.Message(rc), rc);
                return;
            }
            RespondOk(id, EnumerateEntities());
        }

        private static Dictionary<string, object> EnumerateEntities()
        {
            var entities = new List<object>();
            int count = s_getEntityCount();
            for (int i = 0; i < count; i++)
            {
                var nameBuf = new StringBuilder(256);
                int rc = s_getEntityName(i, nameBuf);
                if (rc != Ezd.LMC1_ERR_SUCCESS) continue;
                string name = nameBuf.ToString();

                string text = null;
                var textBuf = new StringBuilder(256);
                int trc = s_getTextByName(name, textBuf);
                if (trc == Ezd.LMC1_ERR_SUCCESS) text = textBuf.ToString();

                entities.Add(new Dictionary<string, object>
                {
                    {"name", name},
                    {"text", text},
                    {"order", i}
                });
            }
            return new Dictionary<string, object>
            {
                {"entities", entities},
                {"count", entities.Count}
            };
        }

        private static void CmdEntities(object id)
        {
            bool ok;
            EnsureReady(id, out ok);
            if (!ok) return;
            RespondOk(id, EnumerateEntities());
        }

        private static void CmdChange(object id, Dictionary<string, object> ps)
        {
            bool ok;
            EnsureReady(id, out ok);
            if (!ok) return;

            string name = GetString(ps, "name");
            string text = GetString(ps, "text");
            if (string.IsNullOrEmpty(name))
            {
                RespondError(id, "INVALID_PARAM", "name is required", null);
                return;
            }
            if (text == null)
            {
                RespondError(id, "INVALID_PARAM", "text is required", null);
                return;
            }

            int rc = s_changeTextByName(name, text);
            if (rc == Ezd.LMC1_ERR_NOFINDENT || rc == Ezd.LMC1_ERR_NOTTEXT)
            {
                RespondError(id, "ENTITY_NOT_FOUND", "text entity '" + name + "': " + Ezd.Message(rc), rc);
                return;
            }
            if (rc != Ezd.LMC1_ERR_SUCCESS)
            {
                RespondError(id, "SDK_ERROR", "lmc1_ChangeTextByName failed: " + Ezd.Message(rc), rc);
                return;
            }
            RespondOk(id, new Dictionary<string, object> {{"name", name}, {"text", text}});
        }

        // Raised while parsing params.design; converted to BAD_REQUEST by CmdDesign so
        // malformed input never surfaces as a NullReference/FormatException INTERNAL.
        private sealed class DesignFormatException : Exception
        {
            public DesignFormatException(string message) : base(message) { }
        }

        private const int BarcodeAttribHumanRead = 0x1000; // BARCODEATTRIB_HUMANREAD
        private const double NaturalSizeEpsilon = 1e-9;

        private sealed class PlanObject
        {
            public string Kind;
            public string Name;
            public string Text;
            public double X, Y, Width, Height;
            public double RotationRad;
            public int Pen;
            // text
            public string Font;
            public double FontHeight;
            public double LetterSpacing;
            public int Align;
            public bool Bold, Italic;
            // barcode
            public int BarcodeType;
            public bool DisplayText;
            public double Module;
            public int ErrorLevel;
            // curve
            public double[] Points;
            public bool Hatch;
            // image
            public string Source;
            // shared rotation center (table sub-lines); 0/0 means "object center".
            public bool HasRotCenter;
            public double RotCx, RotCy;
        }

        private static void CmdDesign(object id, Dictionary<string, object> ps)
        {
            bool ok;
            EnsureReady(id, out ok);
            if (!ok) return;

            if (s_clearEntLib == null || s_addTextToLib == null || s_setTextEntParam4 == null ||
                s_addBarCodeToLib == null || s_addCurveToLib == null || s_addFileToLib == null ||
                s_getEntSize == null || s_scaleEnt == null || s_moveEnt == null || s_rotateEnt == null)
            {
                RespondError(id, "DLL_BIND_FAILED", "MarkEzd.dll is missing design (entity library) exports", null);
                return;
            }

            object designObj;
            if (!ps.TryGetValue("design", out designObj) || designObj == null)
            {
                RespondError(id, "BAD_REQUEST", "params.design is required", null);
                return;
            }

            List<PlanObject> plan;
            try
            {
                plan = ParseDesign(designObj);
            }
            catch (DesignFormatException ex)
            {
                RespondError(id, "BAD_REQUEST", ex.Message, null);
                return;
            }

            // The whole design is validated above, so a rejected request never leaves a
            // half-cleared entity library behind.
            int rc = s_clearEntLib();
            if (rc != Ezd.LMC1_ERR_SUCCESS)
            {
                RespondError(id, "SDK_ERROR", "lmc1_ClearEntLib failed: " + Ezd.Message(rc), rc);
                return;
            }

            foreach (PlanObject obj in plan)
            {
                string failedCall;
                rc = CreateObject(obj, out failedCall);
                if (rc != Ezd.LMC1_ERR_SUCCESS)
                {
                    // Abort immediately: no further objects, no mark; the SDK error code
                    // is reported verbatim so Java never sees a half-built design as ok.
                    RespondError(id, "SDK_ERROR", "design object '" + obj.Name + "': " +
                        failedCall + " failed: " + Ezd.Message(rc), rc);
                    return;
                }
            }

            RespondOk(id, new Dictionary<string, object> {{"created", plan.Count}});
        }

        private static List<object> ReadObjectArray(object value, string what)
        {
            var enumerable = value as System.Collections.IEnumerable;
            if (enumerable == null || value is string || value is System.Collections.IDictionary)
                throw new DesignFormatException(what + " must be an array");
            var result = new List<object>();
            foreach (object item in enumerable)
            {
                result.Add(item);
            }
            return result;
        }

        private static List<PlanObject> ParseDesign(object designObj)
        {
            var design = designObj as Dictionary<string, object>;
            if (design == null) throw new DesignFormatException("params.design must be a JSON object");

            object objsObj;
            if (!design.TryGetValue("objects", out objsObj) || objsObj == null)
                throw new DesignFormatException("design.objects is required");
            var objs = ReadObjectArray(objsObj, "design.objects");
            if (objs.Count == 0) throw new DesignFormatException("design.objects must not be empty");

            double areaWidth = OptNumber(design, "widthMm", 100d);
            double areaHeight = OptNumber(design, "heightMm", 100d);
            if (areaWidth <= 0 || areaHeight <= 0)
                throw new DesignFormatException("design.widthMm/heightMm must be > 0");

            var plan = new List<PlanObject>(objs.Count);
            var names = new HashSet<string>(StringComparer.Ordinal);
            for (int i = 0; i < objs.Count; i++)
            {
                string what = "design.objects[" + i + "]";
                var d = objs[i] as Dictionary<string, object>;
                if (d == null) throw new DesignFormatException(what + " must be a JSON object");
                var obj = ParseObject(d, what, names);
                ConvertToSdkCoordinates(obj, areaWidth, areaHeight);
                plan.Add(obj);
            }
            return plan;
        }

        private static void ConvertToSdkCoordinates(PlanObject o, double areaWidth, double areaHeight)
        {
            // The designer is top-left/Y-down; Ezcad is center-origin/Y-up.
            o.X -= areaWidth / 2d;
            o.Y = areaHeight / 2d - o.Y;
            o.RotationRad = -o.RotationRad;
            if (o.Points != null)
            {
                for (int i = 0; i < o.Points.Length; i += 2)
                {
                    o.Points[i] -= areaWidth / 2d;
                    o.Points[i + 1] = areaHeight / 2d - o.Points[i + 1];
                }
            }
            if (o.HasRotCenter)
            {
                o.RotCx -= areaWidth / 2d;
                o.RotCy = areaHeight / 2d - o.RotCy;
            }
        }

        private static PlanObject ParseObject(Dictionary<string, object> d, string what,
            HashSet<string> names)
        {
            string kind = ReqString(d, "kind", what);
            string name = ReqString(d, "name", what);
            if (name.Length == 0) throw new DesignFormatException(what + ": name must not be empty");
            if (!names.Add(name)) throw new DesignFormatException(what + ": duplicate object name '" + name + "'");

            double x = ReqNumber(d, "x", what);
            double y = ReqNumber(d, "y", what);
            double width = ReqNumber(d, "width", what);
            double height = ReqNumber(d, "height", what);
            if (width < 0 || height < 0)
                throw new DesignFormatException(what + ": width/height must be >= 0");
            double rotationDeg = ReqNumber(d, "rotation", what);
            int pen = ReqInt(d, "pen", what, 0, 255);

            var attrs = GetAttributes(d, what);

            var o = new PlanObject
            {
                Kind = kind,
                Name = name,
                Text = "",
                X = x,
                Y = y,
                Width = width,
                Height = height,
                RotationRad = rotationDeg * Math.PI / 180d, // SDK angles are radians
                Pen = pen,
                Font = "Microsoft YaHei UI",
                Align = 0,
                ErrorLevel = 1,
            };
            double cx, cy;
            if (OptNumber(attrs, "rotationCx", out cx) && OptNumber(attrs, "rotationCy", out cy))
            {
                o.HasRotCenter = true;
                o.RotCx = cx;
                o.RotCy = cy;
            }

            switch (kind)
            {
                case "text":
                    o.Text = ReqString(d, "text", what);
                    if (o.Text.Length == 0)
                        throw new DesignFormatException(what + ": text must not be empty");
                    string font = OptString(attrs, "font", null);
                    if (!string.IsNullOrWhiteSpace(font)) o.Font = font;
                    o.FontHeight = ReqPositiveNumber(attrs, "fontHeight", what + ".attributes");
                    o.LetterSpacing = OptNumber(attrs, "letterSpacing", 0d);
                    o.Align = OptInt(attrs, "align", 0, 2, 0, what + ".attributes");
                    o.Bold = OptBool(attrs, "bold", false);
                    o.Italic = OptBool(attrs, "italic", false);
                    break;
                case "barcode":
                    o.Text = ReqString(d, "text", what);
                    if (o.Text.Length == 0)
                        throw new DesignFormatException(what + ": text (barcode content) must not be empty");
                    o.BarcodeType = ReqInt(attrs, "barcodeType", what + ".attributes", 0, 100);
                    o.Module = ReqPositiveNumber(attrs, "module", what + ".attributes");
                    o.DisplayText = OptBool(attrs, "displayText", false);
                    o.ErrorLevel = OptInt(attrs, "errorLevel", 0, 8, 1, what + ".attributes");
                    break;
                case "curve":
                    o.Points = ParsePoints(attrs, what);
                    o.Hatch = OptBool(attrs, "hatch", false);
                    break;
                case "image":
                    o.Source = OptString(attrs, "source", null);
                    if (string.IsNullOrWhiteSpace(o.Source)) o.Source = OptString(d, "text", null);
                    if (string.IsNullOrWhiteSpace(o.Source))
                        throw new DesignFormatException(what + ": attributes.source (image file path) is required");
                    if (!File.Exists(o.Source))
                        throw new DesignFormatException(what + ": image file does not exist: " + o.Source);
                    break;
                default:
                    throw new DesignFormatException(what + ": unsupported kind '" + kind + "'");
            }

            return o;
        }

        private static int CreateObject(PlanObject o, out string failedCall)
        {
            int rc;
            switch (o.Kind)
            {
                case "text":
                    rc = s_addTextToLib(o.Text, o.Name, o.X, o.Y, 0d, 6, 0d, o.Pen, true);
                    if (rc != Ezd.LMC1_ERR_SUCCESS) { failedCall = "lmc1_AddTextToLib"; return rc; }
                    rc = s_setTextEntParam4(o.Name, o.Font, 0, 0d, o.FontHeight, 1d, 0d,
                        o.LetterSpacing, 0d, 1d, o.Align, o.Bold, o.Italic);
                    if (rc != Ezd.LMC1_ERR_SUCCESS) { failedCall = "lmc1_SetTextEntParam4"; return rc; }
                    break;
                case "barcode":
                    ushort attrib = o.DisplayText ? (ushort)BarcodeAttribHumanRead : (ushort)0;
                    double humanTextHeight = o.DisplayText ? Math.Max(1d, o.Height / 5d) : 0d;
                    var unitScales = new[] { 1d, 1d, 1d, 1d };
                    rc = s_addBarCodeToLib(o.Text, o.Name, o.X, o.Y, 0d, 6, o.Pen, 0,
                        o.BarcodeType, attrib, o.Height, o.Module,
                        unitScales, unitScales, 1d, 1d, 1d, 1d, 1d, 1d,
                        0, 0, o.ErrorLevel, 0, humanTextHeight, 0d, 0d, 0d, 0d, 0d, "Microsoft YaHei");
                    if (rc != Ezd.LMC1_ERR_SUCCESS) { failedCall = "lmc1_AddBarCodeToLib"; return rc; }
                    break;
                case "curve":
                    rc = s_addCurveToLib(o.Points, o.Points.Length / 2, o.Name, o.Pen, o.Hatch ? 1 : 0);
                    if (rc != Ezd.LMC1_ERR_SUCCESS) { failedCall = "lmc1_AddCurveToLib"; return rc; }
                    break;
                case "image":
                    rc = s_addFileToLib(o.Source, o.Name, o.X, o.Y, 0d, 6, 1d, o.Pen, false);
                    if (rc != Ezd.LMC1_ERR_SUCCESS) { failedCall = "lmc1_AddFileToLib"; return rc; }
                    break;
                default:
                    failedCall = "design";
                    return Ezd.LMC1_ERR_PARAM;
            }
            return Calibrate(o, out failedCall);
        }

        // Scale the freshly created entity from its natural size to the design
        // width/height about its natural center (an axis with zero target or zero
        // natural size is left untouched), move its bounding-box top-left corner to
        // the design x/y, then rotate it about the shared rotation center when the
        // design data provides one (table sub-lines) or about its own center.
        private static int Calibrate(PlanObject o, out string failedCall)
        {
            double minx, miny, maxx, maxy, z;
            int rc = s_getEntSize(o.Name, out minx, out miny, out maxx, out maxy, out z);
            if (rc != Ezd.LMC1_ERR_SUCCESS) { failedCall = "lmc1_GetEntSize"; return rc; }

            double natW = maxx - minx;
            double natH = maxy - miny;
            double scaleX = TargetScale(o.Width, natW);
            double scaleY = TargetScale(o.Height, natH);
            if (scaleX != 1d || scaleY != 1d)
            {
                rc = s_scaleEnt(o.Name, (minx + maxx) / 2d, (miny + maxy) / 2d, scaleX, scaleY);
                if (rc != Ezd.LMC1_ERR_SUCCESS) { failedCall = "lmc1_ScaleEnt"; return rc; }
                rc = s_getEntSize(o.Name, out minx, out miny, out maxx, out maxy, out z);
                if (rc != Ezd.LMC1_ERR_SUCCESS) { failedCall = "lmc1_GetEntSize"; return rc; }
            }

            double dx = o.X - minx;
            double dy = o.Y - miny;
            if (dx != 0d || dy != 0d)
            {
                rc = s_moveEnt(o.Name, dx, dy);
                if (rc != Ezd.LMC1_ERR_SUCCESS) { failedCall = "lmc1_MoveEnt"; return rc; }
                minx += dx; maxx += dx;
                miny += dy; maxy += dy;
            }

            if (o.RotationRad != 0d)
            {
                double cx = o.HasRotCenter ? o.RotCx : (minx + maxx) / 2d;
                double cy = o.HasRotCenter ? o.RotCy : (miny + maxy) / 2d;
                rc = s_rotateEnt(o.Name, cx, cy, o.RotationRad);
                if (rc != Ezd.LMC1_ERR_SUCCESS) { failedCall = "lmc1_RotateEnt"; return rc; }
            }

            failedCall = null;
            return Ezd.LMC1_ERR_SUCCESS;
        }

        private static double TargetScale(double target, double natural)
        {
            if (target <= 0 || natural <= NaturalSizeEpsilon) return 1d; // zero-size axis: skip
            double s = target / natural;
            return s > 0 && !double.IsNaN(s) && !double.IsInfinity(s) ? s : 1d;
        }

        private static Dictionary<string, object> GetAttributes(Dictionary<string, object> d, string what)
        {
            object attrsObj;
            if (!d.TryGetValue("attributes", out attrsObj) || attrsObj == null) return null;
            var attrs = attrsObj as Dictionary<string, object>;
            if (attrs == null) throw new DesignFormatException(what + ": attributes must be a JSON object");
            return attrs;
        }

        private static double[] ParsePoints(Dictionary<string, object> attrs, string what)
        {
            object ptsObj;
            if (attrs == null || !attrs.TryGetValue("points", out ptsObj) || ptsObj == null)
                throw new DesignFormatException(what + ".attributes.points is required");
            var pts = ReadObjectArray(ptsObj, what + ".attributes.points");
            if (pts.Count < 4 || pts.Count % 2 != 0)
                throw new DesignFormatException(what + ".attributes.points must hold at least one (x,y) pair");
            var flat = new double[pts.Count];
            for (int i = 0; i < pts.Count; i++)
                flat[i] = ReqNumberValue(pts[i], what + ".attributes.points[" + i + "]");
            return flat;
        }

        private static string ReqString(Dictionary<string, object> d, string key, string what)
        {
            object v;
            if (d == null || !d.TryGetValue(key, out v) || v == null)
                throw new DesignFormatException(what + ": missing '" + key + "'");
            string s = v as string;
            if (s == null) throw new DesignFormatException(what + ": '" + key + "' must be a string");
            return s;
        }

        private static string OptString(Dictionary<string, object> d, string key, string def)
        {
            object v;
            if (d == null || !d.TryGetValue(key, out v) || v == null) return def;
            string s = v as string;
            return s ?? def;
        }

        private static double ReqNumber(Dictionary<string, object> d, string key, string what)
        {
            object v;
            if (d == null || !d.TryGetValue(key, out v) || v == null)
                throw new DesignFormatException(what + ": missing numeric '" + key + "'");
            return ReqNumberValue(v, what + "." + key);
        }

        private static double ReqNumberValue(object v, string what)
        {
            double n;
            if (!TryNumber(v, out n))
                throw new DesignFormatException(what + " must be a number (got '" + v + "')");
            if (double.IsNaN(n) || double.IsInfinity(n))
                throw new DesignFormatException(what + " must be a finite number");
            return n;
        }

        private static double ReqPositiveNumber(Dictionary<string, object> d, string key, string what)
        {
            double n = ReqNumber(d, key, what);
            if (n <= 0) throw new DesignFormatException(what + "." + key + " must be > 0");
            return n;
        }

        private static double OptNumber(Dictionary<string, object> d, string key, double def)
        {
            object v;
            if (d == null || !d.TryGetValue(key, out v) || v == null) return def;
            double n;
            return TryNumber(v, out n) && !double.IsNaN(n) && !double.IsInfinity(n) ? n : def;
        }

        private static bool OptNumber(Dictionary<string, object> d, string key, out double value)
        {
            object v;
            value = 0;
            if (d == null || !d.TryGetValue(key, out v) || v == null) return false;
            double n;
            if (!TryNumber(v, out n) || double.IsNaN(n) || double.IsInfinity(n)) return false;
            value = n;
            return true;
        }

        private static bool TryNumber(object v, out double n)
        {
            n = 0;
            if (v == null || v is bool) return false;
            if (v is double) { n = (double)v; return true; }
            string s = v as string;
            if (s != null)
                return double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out n);
            try
            {
                n = Convert.ToDouble(v, CultureInfo.InvariantCulture);
                return true;
            }
            catch (Exception)
            {
                return false;
            }
        }

        private static int ReqInt(Dictionary<string, object> d, string key, string what, int min, int max)
        {
            object v;
            if (d == null || !d.TryGetValue(key, out v) || v == null)
                throw new DesignFormatException(what + ": missing '" + key + "'");
            return CheckedInt(v, what + "." + key, min, max);
        }

        private static int OptInt(Dictionary<string, object> d, string key, int min, int max, int def, string what)
        {
            object v;
            if (d == null || !d.TryGetValue(key, out v) || v == null) return def;
            return CheckedInt(v, what + "." + key, min, max);
        }

        private static int CheckedInt(object v, string what, int min, int max)
        {
            double n;
            if (!TryNumber(v, out n) || double.IsNaN(n) || double.IsInfinity(n))
                throw new DesignFormatException(what + " must be an integer");
            int i = (int)Math.Round(n);
            if (i < min || i > max)
                throw new DesignFormatException(what + " must be within " + min + ".." + max);
            return i;
        }

        private static bool OptBool(Dictionary<string, object> d, string key, bool def)
        {
            object v;
            if (d == null || !d.TryGetValue(key, out v) || v == null) return def;
            if (v is bool) return (bool)v;
            string s = v as string;
            if (s != null)
            {
                bool b;
                if (bool.TryParse(s, out b)) return b;
                return def;
            }
            double n;
            return TryNumber(v, out n) ? n != 0 : def;
        }

        private static void CmdMark(object id, Dictionary<string, object> ps)
        {
            lock (StateLock)
            {
                if (s_closed) { RespondError(id, "CLOSED", "bridge already closed", null); return; }
                if (s_initHung) { RespondError(id, "INIT_HUNG", "SDK initialization is stuck; only close is available", null); return; }
                if (!s_initialized) { RespondError(id, "NOT_INITIALIZED", "SDK not initialized", null); return; }
                if (s_marking) { RespondError(id, "ALREADY_MARKING", "a mark job is already running", null); return; }
                s_marking = true;
                s_markGeneration++;
            }

            bool fly = GetBool(ps, "fly", false);
            int generation = s_markGeneration;

            RespondOk(id, new Dictionary<string, object>
            {
                {"started", true},
                {"fly", fly},
                {"generation", generation}
            });

            ThreadPool.QueueUserWorkItem(_ =>
            {
                int rc = Ezd.LMC1_ERR_UNKNOW;
                Exception failure = null;
                try
                {
                    rc = s_mark(fly);
                }
                catch (Exception ex)
                {
                    failure = ex;
                }
                lock (StateLock)
                {
                    if (generation == s_markGeneration) s_marking = false;
                }
                if (failure != null)
                {
                    EmitEvent("markFinished", new Dictionary<string, object>
                    {
                        {"code", Ezd.LMC1_ERR_UNKNOW},
                        {"error", failure.GetType().Name + ": " + failure.Message}
                    });
                }
                else
                {
                    EmitEvent("markFinished", new Dictionary<string, object>
                    {
                        {"code", rc},
                        {"message", Ezd.Message(rc)}
                    });
                }
            });
        }

        private static void CmdRedLight(object id)
        {
            lock (StateLock)
            {
                if (s_closed) { RespondError(id, "CLOSED", "bridge already closed", null); return; }
                if (s_initHung) { RespondError(id, "INIT_HUNG", "SDK initialization is stuck; only close is available", null); return; }
                if (!s_initialized) { RespondError(id, "NOT_INITIALIZED", "SDK not initialized", null); return; }
                if (s_marking) { RespondError(id, "ALREADY_MARKING", "a mark job is already running", null); return; }
                if (s_redLightMark == null)
                {
                    RespondError(id, "DLL_BIND_FAILED", "MarkEzd.dll is missing lmc1_RedLightMark", null);
                    return;
                }
                s_marking = true;
                s_markGeneration++;
            }

            int generation = s_markGeneration;
            RespondOk(id, new Dictionary<string, object>
            {
                {"started", true},
                {"redLight", true},
                {"generation", generation}
            });

            ThreadPool.QueueUserWorkItem(_ =>
            {
                int rc = Ezd.LMC1_ERR_UNKNOW;
                Exception failure = null;
                try
                {
                    rc = Ezd.LMC1_ERR_SUCCESS;
                    while (true)
                    {
                        lock (StateLock)
                        {
                            if (generation != s_markGeneration || !s_marking) break;
                        }
                        rc = s_redLightMark();
                        if (rc == Ezd.LMC1_ERR_USERSTOP)
                        {
                            rc = Ezd.LMC1_ERR_SUCCESS;
                            break;
                        }
                        if (rc != Ezd.LMC1_ERR_SUCCESS) break;
                        Thread.Sleep(10);
                    }
                }
                catch (Exception ex)
                {
                    failure = ex;
                }
                lock (StateLock)
                {
                    if (generation == s_markGeneration) s_marking = false;
                }
                if (failure != null)
                {
                    EmitEvent("markFinished", new Dictionary<string, object>
                    {
                        {"code", Ezd.LMC1_ERR_UNKNOW},
                        {"error", failure.GetType().Name + ": " + failure.Message}
                    });
                }
                else
                {
                    EmitEvent("markFinished", new Dictionary<string, object>
                    {
                        {"code", rc},
                        {"message", Ezd.Message(rc)}
                    });
                }
            });
        }

        private static void CmdStop(object id)
        {
            lock (StateLock)
            {
                if (s_closed) { RespondError(id, "CLOSED", "bridge already closed", null); return; }
                if (s_initHung) { RespondError(id, "INIT_HUNG", "SDK initialization is stuck; only close is available", null); return; }
                if (!s_initialized) { RespondError(id, "NOT_INITIALIZED", "SDK not initialized", null); return; }
                if (!s_marking) { RespondError(id, "NOT_MARKING", "no mark job is running", null); return; }
            }
            // lmc1_StopMark is documented to be called from another thread than lmc1_Mark;
            // the request loop runs on its own thread while the mark worker sits inside lmc1_Mark.
            int rc = s_stopMark();
            if (rc != Ezd.LMC1_ERR_SUCCESS)
            {
                RespondError(id, "SDK_ERROR", "lmc1_StopMark failed: " + Ezd.Message(rc), rc);
                return;
            }
            RespondOk(id, new Dictionary<string, object> {{"stopped", true}});
        }

        private static void CmdClose(object id)
        {
            lock (StateLock)
            {
                if (s_closed) { RespondError(id, "CLOSED", "bridge already closed", null); return; }
                if (s_marking) { RespondError(id, "BUSY_MARKING", "stop the running mark job before close", null); return; }
                s_closed = true;
                s_initialized = false;
            }
            int rc = ShutdownSdk();
            if (rc == Ezd.LMC1_ERR_SUCCESS)
            {
                RespondOk(id, new Dictionary<string, object> {{"closed", true}});
            }
            else
            {
                RespondError(id, "SDK_ERROR", "lmc1_Close failed: " + Ezd.Message(rc), rc);
            }
        }

        // lmc1_Close must never be invoked twice; guarded by s_closed.
        // The SDK DLLs must NOT be FreeLibrary'd mid-process: unloading and reloading
        // them breaks their DllMain (verified empirically: loads then fail with
        // win32 error 126). Process exit is the only safe unload point.
        private static int ShutdownSdk()
        {
            int rc = Ezd.LMC1_ERR_SUCCESS;
            if (s_close != null)
            {
                try { rc = s_close(); }
                catch { rc = Ezd.LMC1_ERR_UNKNOW; }
                s_close = null;
            }
            lock (StateLock)
            {
                s_initialized = false;
                s_marking = false;
            }
            return rc;
        }
    }
}
