// FireRemote.cs - a small desktop remote for Amazon Fire TV (and other Android TV devices) over ADB.
//
//  * Instant keys: starts the official scrcpy-server (Genymobile, Apache-2.0) found in your scrcpy
//    folder in control-only mode (no video, no audio) and sends key events over its control socket.
//  * Fallback: "adb shell input keyevent" through one persistent shell (slower, but works anywhere).
//
// Build on any Windows 10/11 PC, no SDK needed (C# 5, .NET Framework 4.x which ships with Windows):
//   "%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:winexe /optimize+
//       /win32icon:FireRemote.ico /out:FireRemote.exe FireRemote.cs
//
// MIT licence. Keep the source file ASCII-only: csc reads files without a BOM in the ANSI code page.

using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

[assembly: System.Reflection.AssemblyTitle("Fire Remote")]
[assembly: System.Reflection.AssemblyProduct("Fire Remote")]
[assembly: System.Reflection.AssemblyDescription("Desktop remote control for Fire TV over ADB")]
[assembly: System.Reflection.AssemblyVersion("1.0.0.0")]
[assembly: System.Reflection.AssemblyFileVersion("1.0.0.0")]

namespace FireRemote
{
    // ------------------------------------------------------------------ entry point

    static class Program
    {
        internal const string AppVersion = "1.0";
        internal static readonly string DataDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "FireRemote");

        [DllImport("user32.dll")]
        static extern bool SetProcessDPIAware();

        [STAThread]
        static void Main()
        {
            if (Tools.IsWindows)
            {
                try { SetProcessDPIAware(); } catch { }
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
            Application.ThreadException += delegate (object s, ThreadExceptionEventArgs e) { ReportCrash(e.Exception); };
            AppDomain.CurrentDomain.UnhandledException += delegate (object s, UnhandledExceptionEventArgs e) { ReportCrash(e.ExceptionObject as Exception); };

            bool first = true;
            Mutex mutex = null;
            try { mutex = new Mutex(true, "FireRemote-single-instance", out first); } catch { first = true; }
            if (!first)
            {
                try { EventWaitHandle.OpenExisting("FireRemote-activate").Set(); } catch { }
                return;
            }
            var form = new RemoteForm();
            ListenForActivation(form);
            Application.Run(form);
            GC.KeepAlive(mutex);
        }

        // A second launch just brings the running window to the front.
        static void ListenForActivation(RemoteForm form)
        {
            EventWaitHandle ev;
            try { ev = new EventWaitHandle(false, EventResetMode.AutoReset, "FireRemote-activate"); }
            catch { return; }
            var t = new Thread(delegate ()
            {
                while (true)
                {
                    ev.WaitOne();
                    try { form.BeginInvoke(new Action(form.ShowFromOtherInstance)); }
                    catch { return; }
                }
            });
            t.IsBackground = true;
            t.Start();
        }

        internal static void ReportCrash(Exception ex)
        {
            string log = Path.Combine(DataDir, "crash.log");
            try
            {
                Directory.CreateDirectory(DataDir);
                File.AppendAllText(log, DateTime.Now.ToString("s", CultureInfo.InvariantCulture) + "  " + ex + Environment.NewLine + Environment.NewLine);
            }
            catch { }
            try
            {
                MessageBox.Show("Something went wrong:\n\n" + (ex != null ? ex.Message : "unknown error") +
                    "\n\nDetails were saved to:\n" + log, "Fire Remote", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
            catch { }
        }
    }

    // ------------------------------------------------------------------ settings

    sealed class Settings
    {
        public const int MaxFavorites = 6;
        public string Ip = "";
        public string AdbPath = "";
        public bool TopMost;
        public int X = int.MinValue;
        public int Y = int.MinValue;
        public List<AppEntry> Favorites = new List<AppEntry>();
        readonly object gate = new object();

        static string FilePath { get { return Path.Combine(Program.DataDir, "settings.ini"); } }

        public static Settings Load()
        {
            var st = new Settings();
            try
            {
                if (!File.Exists(FilePath)) return st;
                foreach (var line in File.ReadAllLines(FilePath))
                {
                    int i = line.IndexOf('=');
                    if (i <= 0) continue;
                    string k = line.Substring(0, i).Trim();
                    string v = line.Substring(i + 1).Trim();
                    int n;
                    switch (k)
                    {
                        case "ip": st.Ip = v; break;
                        case "adb": st.AdbPath = v; break;
                        case "topmost": st.TopMost = v == "1"; break;
                        case "x": if (int.TryParse(v, NumberStyles.Integer, CultureInfo.InvariantCulture, out n)) st.X = n; break;
                        case "y": if (int.TryParse(v, NumberStyles.Integer, CultureInfo.InvariantCulture, out n)) st.Y = n; break;
                        case "fav":
                            int bar = v.IndexOf('|');
                            if (bar > 0 && bar < v.Length - 1 && st.Favorites.Count < MaxFavorites)
                                st.Favorites.Add(new AppEntry { Name = v.Substring(0, bar), Package = v.Substring(bar + 1) });
                            break;
                    }
                }
            }
            catch { }
            return st;
        }

        public void Save()
        {
            lock (gate)
            {
                try
                {
                    Directory.CreateDirectory(Program.DataDir);
                    var lines = new List<string>
                    {
                        "ip=" + Ip,
                        "adb=" + AdbPath,
                        "topmost=" + (TopMost ? "1" : "0"),
                        "x=" + X.ToString(CultureInfo.InvariantCulture),
                        "y=" + Y.ToString(CultureInfo.InvariantCulture)
                    };
                    foreach (var f in Favorites.ToArray())
                        lines.Add("fav=" + (f.Name ?? "").Replace("|", " ").Replace("\r", " ").Replace("\n", " ") + "|" + f.Package);
                    File.WriteAllLines(FilePath, lines.ToArray());
                }
                catch { }
            }
        }
    }

    // ------------------------------------------------------------------ processes, adb, file search

    sealed class ProcResult
    {
        public int ExitCode = -1;
        public string Out = "";
        public string Err = "";
        public bool TimedOut;
        public string All { get { return (Out + "\n" + Err).Trim(); } }
    }

    static class Proc
    {
        public static ProcessStartInfo Info(string exe, string args, bool redirect)
        {
            var psi = new ProcessStartInfo(exe, args);
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.WindowStyle = ProcessWindowStyle.Hidden;
            try { psi.WorkingDirectory = Path.GetDirectoryName(exe); } catch { }
            if (redirect)
            {
                psi.RedirectStandardInput = true;
                psi.RedirectStandardOutput = true;
                psi.RedirectStandardError = true;
                psi.StandardOutputEncoding = Encoding.UTF8;
                psi.StandardErrorEncoding = Encoding.UTF8;
            }
            return psi;
        }

        // Runs a command and collects its output. Never blocks longer than timeout (+ 2 s grace for output).
        public static ProcResult Run(string exe, string args, int timeoutMs)
        {
            var r = new ProcResult();
            var outText = new StringBuilder();
            var errText = new StringBuilder();
            var outDone = new ManualResetEvent(false);
            var errDone = new ManualResetEvent(false);
            using (var p = new Process())
            {
                p.StartInfo = Info(exe, args, true);
                p.OutputDataReceived += delegate (object s, DataReceivedEventArgs e)
                {
                    if (e.Data == null) outDone.Set();
                    else lock (outText) outText.AppendLine(e.Data);
                };
                p.ErrorDataReceived += delegate (object s, DataReceivedEventArgs e)
                {
                    if (e.Data == null) errDone.Set();
                    else lock (errText) errText.AppendLine(e.Data);
                };
                try { p.Start(); }
                catch (Exception ex) { r.Err = ex.Message; return r; }
                try { p.StandardInput.Close(); } catch { }
                p.BeginOutputReadLine();
                p.BeginErrorReadLine();
                if (!p.WaitForExit(timeoutMs))
                {
                    r.TimedOut = true;
                    try { p.Kill(); } catch { }
                }
                outDone.WaitOne(1000);
                errDone.WaitOne(1000);
                try { if (!r.TimedOut) r.ExitCode = p.ExitCode; } catch { }
            }
            lock (outText) r.Out = outText.ToString();
            lock (errText) r.Err = errText.ToString();
            return r;
        }

        // Runs without redirecting output (used for "adb start-server" so the daemon never inherits our pipes).
        public static void RunQuiet(string exe, string args, int timeoutMs)
        {
            try
            {
                using (var p = Process.Start(Info(exe, args, false)))
                {
                    if (p != null && !p.WaitForExit(timeoutMs))
                    {
                        try { p.Kill(); } catch { }
                    }
                }
            }
            catch { }
        }
    }

    sealed class AdbTool
    {
        public readonly string ExePath;
        public AdbTool(string exePath) { ExePath = exePath; }
        public ProcResult Run(string args, int timeoutMs) { return Proc.Run(ExePath, args, timeoutMs); }
        public ProcResult On(string serial, string args, int timeoutMs) { return Run("-s " + serial + " " + args, timeoutMs); }
    }

    static class Tools
    {
        public static bool IsWindows { get { return Path.DirectorySeparatorChar == '\\'; } }

        public static string Exe(string name) { return IsWindows ? name + ".exe" : name; }

        public static string AppDir
        {
            get { return Path.GetDirectoryName(Path.GetFullPath(Application.ExecutablePath)); }
        }

        static readonly Regex RelevantDir = new Regex("scrcpy|platform-tools|android|adb", RegexOptions.IgnoreCase);

        static List<string> SearchRoots()
        {
            var roots = new List<string>();
            roots.Add(AppDir);
            string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            if (!string.IsNullOrEmpty(home))
            {
                roots.Add(Path.Combine(home, "Downloads"));
                roots.Add(Path.Combine(home, "Desktop"));
                roots.Add(Path.Combine(home, "Documents"));
                roots.Add(home);
            }
            string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            if (!string.IsNullOrEmpty(local)) roots.Add(Path.Combine(local, "Android"));
            string pf = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
            if (!string.IsNullOrEmpty(pf)) roots.Add(pf);
            if (IsWindows)
            {
                try { roots.Add(Path.GetPathRoot(Environment.SystemDirectory)); } catch { }
            }
            var result = new List<string>();
            foreach (var r in roots)
            {
                try
                {
                    if (!string.IsNullOrEmpty(r) && Directory.Exists(r) &&
                        !result.Any(x => string.Equals(x, r, StringComparison.OrdinalIgnoreCase)))
                        result.Add(r);
                }
                catch { }
            }
            return result;
        }

        // Looks for files by name under the usual places, at most 3 folders deep. At the first level only
        // folders that look relevant (scrcpy, platform-tools, android, adb) are entered, to keep it fast.
        public static List<string> FindFiles(string[] names)
        {
            var found = new List<string>();
            var visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var root in SearchRoots())
                Walk(root, 0, names, found, visited);
            return found;
        }

        static void Walk(string dir, int depth, string[] names, List<string> found, HashSet<string> visited)
        {
            if (!visited.Add(dir)) return;
            foreach (var n in names)
            {
                try
                {
                    string p = Path.Combine(dir, n);
                    if (File.Exists(p) && !found.Any(f => string.Equals(f, p, StringComparison.OrdinalIgnoreCase))) found.Add(p);
                }
                catch { }
            }
            if (depth >= 3) return;
            string[] subs;
            try { subs = Directory.GetDirectories(dir); } catch { return; }
            foreach (var sub in subs)
            {
                string name = Path.GetFileName(sub) ?? "";
                if (name.StartsWith(".") || name.StartsWith("$")) continue;
                if (depth == 0 && !RelevantDir.IsMatch(name)) continue;
                Walk(sub, depth + 1, names, found, visited);
            }
        }

        public static string ServerFileIn(string dir)
        {
            if (string.IsNullOrEmpty(dir)) return null;
            foreach (var n in new[] { "scrcpy-server", "scrcpy-server.jar" })
            {
                try
                {
                    string p = Path.Combine(dir, n);
                    if (File.Exists(p)) return p;
                }
                catch { }
            }
            return null;
        }

        static bool IsQt(string path) { return path.IndexOf("qtscrcpy", StringComparison.OrdinalIgnoreCase) >= 0; }

        // Prefers the adb that ships with the official scrcpy release (it sits next to scrcpy-server).
        public static string FindAdb(string saved)
        {
            if (!string.IsNullOrEmpty(saved) && File.Exists(saved)) return saved;
            string adbName = Exe("adb");
            var hits = FindFiles(new[] { adbName });
            string best = hits
                .OrderBy(p => ServerFileIn(Path.GetDirectoryName(p)) != null && !IsQt(p) ? 0 : (!IsQt(p) ? 1 : 2))
                .FirstOrDefault();
            if (best != null) return best;
            foreach (var d in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
            {
                try
                {
                    string p = Path.Combine(d.Trim().Trim('"'), adbName);
                    if (File.Exists(p)) return p;
                }
                catch { }
            }
            return null;
        }

        public static List<string> FindServers(string adbPath)
        {
            var list = new List<string>();
            Action<string> add = delegate (string file)
            {
                if (file != null && !list.Any(x => string.Equals(x, file, StringComparison.OrdinalIgnoreCase))) list.Add(file);
            };
            add(ServerFileIn(AppDir));
            if (adbPath != null) add(ServerFileIn(Path.GetDirectoryName(adbPath)));
            foreach (var f in FindFiles(new[] { "scrcpy-server", "scrcpy-server.jar" })) add(f);
            return list.OrderBy(f => IsQt(f) ? 1 : 0).ToList();
        }

        public static string FindScrcpy(string serverPath, string adbPath)
        {
            var dirs = new[]
            {
                serverPath != null ? Path.GetDirectoryName(serverPath) : null,
                adbPath != null ? Path.GetDirectoryName(adbPath) : null,
                AppDir
            };
            foreach (var dir in dirs)
            {
                if (dir == null) continue;
                string p = Path.Combine(dir, Exe("scrcpy"));
                if (File.Exists(p)) return p;
            }
            return null;
        }

        // scrcpy-server refuses to start unless the client passes its exact version.
        public static string GuessServerVersion(string serverPath)
        {
            string dir = Path.GetDirectoryName(serverPath) ?? "";
            string exe = Path.Combine(dir, Exe("scrcpy"));
            if (File.Exists(exe))
            {
                var r = Proc.Run(exe, "--version", 8000);
                var m = Regex.Match(r.Out + "\n" + r.Err, @"scrcpy\s+v?(\d+\.\d+(?:\.\d+)?)");
                if (m.Success) return m.Groups[1].Value;
            }
            var ms = Regex.Matches(dir, @"[vV](\d+\.\d+(?:\.\d+)?)");
            if (ms.Count > 0) return ms[ms.Count - 1].Groups[1].Value;
            return "5.0.1";
        }
    }

    // ------------------------------------------------------------------ network scan

    static class Net
    {
        public static List<IPAddress> LocalIPv4()
        {
            var list = new List<IPAddress>();
            try
            {
                foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                    foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                        if (ua.Address.AddressFamily == AddressFamily.InterNetwork) list.Add(ua.Address);
                }
            }
            catch { }
            return list;
        }

        // Only networks this PC hosts (it is the x.y.z.1 router, e.g. the Windows hotspot 192.168.137.1),
        // plus the network of a typed IP if this PC is on it. We never sweep a campus or public network.
        public static List<string> ScanPrefixes(string hintIp)
        {
            var result = new List<string>();
            foreach (var addr in LocalIPv4())
            {
                var b = addr.GetAddressBytes();
                bool priv = b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168);
                if (!priv) continue;
                string prefix = b[0] + "." + b[1] + "." + b[2];
                bool router = b[3] == 1;
                bool hinted = !string.IsNullOrEmpty(hintIp) && hintIp.StartsWith(prefix + ".", StringComparison.Ordinal);
                if ((router || hinted) && !result.Contains(prefix)) result.Add(prefix);
            }
            return result;
        }

        public static List<string> Scan(IEnumerable<string> prefixes, int port, int waitMs)
        {
            var own = new HashSet<string>(LocalIPv4().Select(a => a.ToString()));
            var probes = new List<KeyValuePair<string, TcpClient>>();
            var results = new List<IAsyncResult>();
            foreach (var prefix in prefixes)
            {
                for (int i = 1; i < 255; i++)
                {
                    string ip = prefix + "." + i.ToString(CultureInfo.InvariantCulture);
                    if (own.Contains(ip)) continue;
                    var c = new TcpClient();
                    try
                    {
                        var ar = c.BeginConnect(IPAddress.Parse(ip), port, null, null);
                        results.Add(ar);
                        probes.Add(new KeyValuePair<string, TcpClient>(ip, c));
                    }
                    catch { c.Close(); }
                }
            }
            Thread.Sleep(waitMs);
            var found = new List<string>();
            for (int i = 0; i < probes.Count; i++)
            {
                var c = probes[i].Value;
                try
                {
                    if (results[i].IsCompleted)
                    {
                        c.EndConnect(results[i]);
                        if (c.Connected) found.Add(probes[i].Key);
                    }
                }
                catch { }
                try { c.Close(); } catch { }
            }
            return found;
        }
    }

    // ------------------------------------------------------------------ control channels

    interface IChannel
    {
        bool Instant { get; }
        void Key(int keycode);
        void Text(string text);
        void StartApp(string package);
        void Close(bool wait);
        event Action<string> Lost;
    }

    sealed class ServerInfo
    {
        public string Path;
        public string Version;
    }

    // Talks to scrcpy-server's control socket (protocol checked against scrcpy 5.0.1):
    //   INJECT_KEYCODE = [0][action u8][keycode i32][repeat i32][metastate i32]   (big-endian)
    //   INJECT_TEXT    = [1][length u32][utf-8]                                    (max 300)
    //   SET_CLIPBOARD  = [9][sequence u64][paste u8][length u32][utf-8]
    //   START_APP      = [16][length u8][utf-8 package name]
    sealed class FastChannel : IChannel
    {
        public const string DeviceJar = "/data/local/tmp/fireremote-server.jar";

        readonly AdbTool adb;
        readonly string serial;
        readonly BlockingCollection<byte[]> outbox = new BlockingCollection<byte[]>(512);
        Process server;
        TcpClient client;
        NetworkStream stream;
        int port;
        volatile bool closing;
        int failed;

        public event Action<string> Lost;
        public bool Instant { get { return true; } }

        FastChannel(AdbTool adb, string serial)
        {
            this.adb = adb;
            this.serial = serial;
        }

        public static FastChannel Open(AdbTool adb, string serial, ServerInfo info, out string error)
        {
            error = null;
            var push = adb.On(serial, "push \"" + info.Path + "\" " + DeviceJar, 90000);
            if (push.TimedOut || push.ExitCode != 0)
            {
                error = "could not copy scrcpy-server to the TV (" + LastLine(push.All) + ")";
                return null;
            }
            try
            {
                string version = info.Version;
                for (int attempt = 0; attempt < 2; attempt++)
                {
                    var ch = new FastChannel(adb, serial);
                    string log;
                    if (ch.Start(version, out log))
                    {
                        info.Version = version;
                        return ch;
                    }
                    ch.Shutdown(true);
                    var m = Regex.Match(log ?? "", @"server version \(([^)\s]+)\) does not match");
                    if (m.Success && m.Groups[1].Value != version)
                    {
                        version = m.Groups[1].Value;   // retry once with the version the server reported
                        continue;
                    }
                    error = string.IsNullOrEmpty(log) ? "the server did not answer" : LastLine(log);
                    return null;
                }
                error = "scrcpy-server version mismatch";
                return null;
            }
            finally
            {
                // The running server keeps the file open, so it can be deleted right away (scrcpy does the same).
                ThreadPool.QueueUserWorkItem(delegate { adb.On(serial, "shell rm -f " + DeviceJar, 10000); });
            }
        }

        internal static string LastLine(string text)
        {
            var lines = (text ?? "").Replace("\r", "").Split('\n').Select(l => l.Trim()).Where(l => l.Length > 0).ToArray();
            string pick = lines.LastOrDefault(l => l.IndexOf("error", StringComparison.OrdinalIgnoreCase) >= 0) ?? lines.LastOrDefault() ?? "no output";
            return pick.Length > 160 ? pick.Substring(0, 160) + "..." : pick;
        }

        bool Start(string version, out string log)
        {
            log = null;
            var output = new StringBuilder();
            int scid = new Random(Guid.NewGuid().GetHashCode()).Next(1, int.MaxValue);
            string scidHex = scid.ToString("x8", CultureInfo.InvariantCulture);
            port = FreePort();
            var fwd = adb.On(serial, "forward tcp:" + port.ToString(CultureInfo.InvariantCulture) + " localabstract:scrcpy_" + scidHex, 15000);
            if (fwd.TimedOut || fwd.ExitCode != 0)
            {
                log = "adb forward failed: " + fwd.All;
                port = 0;
                return false;
            }

            string args = "-s " + serial + " shell CLASSPATH=" + DeviceJar + " app_process / com.genymobile.scrcpy.Server " + version
                + " scid=" + scidHex + " log_level=info video=false audio=false control=true tunnel_forward=true"
                + " cleanup=false clipboard_autosync=false send_device_meta=false power_on=true";
            server = new Process();
            server.StartInfo = Proc.Info(adb.ExePath, args, true);
            DataReceivedEventHandler collect = delegate (object s, DataReceivedEventArgs e)
            {
                if (e.Data == null) return;
                lock (output) { if (output.Length < 32000) output.AppendLine(e.Data); }
            };
            server.OutputDataReceived += collect;
            server.ErrorDataReceived += collect;
            try { server.Start(); }
            catch (Exception ex) { log = ex.Message; return false; }
            server.BeginOutputReadLine();
            server.BeginErrorReadLine();

            // adb accepts the local TCP connection even before the server listens on the TV, so success is
            // only the dummy byte the server writes once it has accepted us (same check as the scrcpy client).
            DateTime deadline = DateTime.UtcNow.AddSeconds(20);
            while (DateTime.UtcNow < deadline && !closing)
            {
                bool exited = false;
                try { exited = server.HasExited; } catch { }
                if (exited)
                {
                    Thread.Sleep(300);
                    break;
                }
                var c = new TcpClient();
                try
                {
                    c.NoDelay = true;
                    c.Connect(IPAddress.Loopback, port);
                    var st = c.GetStream();
                    st.ReadTimeout = 2500;
                    int b = st.ReadByte();
                    if (b == 0)
                    {
                        st.ReadTimeout = Timeout.Infinite;
                        client = c;
                        stream = st;
                        var reader = new Thread(ReadLoop);
                        reader.IsBackground = true;
                        reader.Name = "fr-read";
                        reader.Start();
                        var writer = new Thread(WriteLoop);
                        writer.IsBackground = true;
                        writer.Name = "fr-write";
                        writer.Start();
                        return true;
                    }
                }
                catch (IOException) { }
                catch (SocketException) { }
                catch (ObjectDisposedException) { }
                try { c.Close(); } catch { }
                Thread.Sleep(200);
            }
            lock (output) log = output.ToString();
            if (string.IsNullOrEmpty(log)) log = "timed out waiting for the server";
            return false;
        }

        void ReadLoop()
        {
            // The server only sends clipboard/ack/uhid messages, which we don't use: drain until EOF.
            var buf = new byte[4096];
            try
            {
                while (!closing)
                {
                    int n = stream.Read(buf, 0, buf.Length);
                    if (n <= 0) break;
                }
            }
            catch { }
            Fail("Connection to the TV was lost");
        }

        void WriteLoop()
        {
            try
            {
                foreach (var msg in outbox.GetConsumingEnumerable())
                    stream.Write(msg, 0, msg.Length);
            }
            catch (Exception)
            {
                Fail("Connection to the TV was lost");
            }
        }

        void Fail(string why)
        {
            if (closing) return;
            if (Interlocked.Exchange(ref failed, 1) == 1) return;
            Shutdown(false);
            var h = Lost;
            if (h != null) h(why);
        }

        public void Close(bool wait)
        {
            closing = true;
            Shutdown(wait);
        }

        void Shutdown(bool wait)
        {
            try { outbox.CompleteAdding(); } catch { }
            try { if (client != null) client.Close(); } catch { }
            try { if (server != null && !server.HasExited) server.Kill(); } catch { }
            int p = Interlocked.Exchange(ref port, 0);
            if (p > 0)
            {
                ThreadStart remove = delegate { adb.On(serial, "forward --remove tcp:" + p.ToString(CultureInfo.InvariantCulture), 5000); };
                if (wait) remove();
                else ThreadPool.QueueUserWorkItem(delegate { remove(); });
            }
        }

        void Send(byte[] message)
        {
            if (closing || failed != 0) return;
            try { outbox.TryAdd(message); } catch (InvalidOperationException) { }
        }

        public void Key(int keycode)
        {
            var b = new byte[28];
            WriteKey(b, 0, 0, keycode);   // ACTION_DOWN
            WriteKey(b, 14, 1, keycode);  // ACTION_UP
            Send(b);
        }

        static void WriteKey(byte[] b, int o, int action, int keycode)
        {
            b[o] = 0;
            b[o + 1] = (byte)action;
            PutInt(b, o + 2, keycode);
            PutInt(b, o + 6, 0);
            PutInt(b, o + 10, 0);
        }

        internal static void PutInt(byte[] b, int o, int v)
        {
            b[o] = (byte)(v >> 24);
            b[o + 1] = (byte)(v >> 16);
            b[o + 2] = (byte)(v >> 8);
            b[o + 3] = (byte)v;
        }

        public void Text(string text)
        {
            foreach (var chunk in Chunks(text, 300))
            {
                byte[] data = Encoding.UTF8.GetBytes(chunk);
                if (chunk.All(ch => ch >= 0x20 && ch <= 0x7E))
                {
                    var b = new byte[5 + data.Length];
                    b[0] = 1;
                    PutInt(b, 1, data.Length);
                    Buffer.BlockCopy(data, 0, b, 5, data.Length);
                    Send(b);
                }
                else
                {
                    // Accents, emoji, Arabic... can't be typed by the virtual keyboard: put them on the TV
                    // clipboard and press PASTE instead (sequence 0 = no acknowledgement wanted).
                    var b = new byte[14 + data.Length];
                    b[0] = 9;
                    b[9] = 1;
                    PutInt(b, 10, data.Length);
                    Buffer.BlockCopy(data, 0, b, 14, data.Length);
                    Send(b);
                }
            }
        }

        internal static IEnumerable<string> Chunks(string text, int maxBytes)
        {
            if (string.IsNullOrEmpty(text)) yield break;
            int start = 0, bytes = 0;
            for (int i = 0; i < text.Length; i++)
            {
                int len = char.IsHighSurrogate(text[i]) && i + 1 < text.Length ? 2 : 1;
                int nb = Encoding.UTF8.GetByteCount(text.Substring(i, len));
                if (bytes + nb > maxBytes && i > start)
                {
                    yield return text.Substring(start, i - start);
                    start = i;
                    bytes = 0;
                }
                bytes += nb;
                i += len - 1;
            }
            if (start < text.Length) yield return text.Substring(start);
        }

        public void StartApp(string package)
        {
            byte[] data = Encoding.UTF8.GetBytes(package ?? "");
            if (data.Length == 0 || data.Length > 255) return;
            var b = new byte[2 + data.Length];
            b[0] = 16;
            b[1] = (byte)data.Length;
            Buffer.BlockCopy(data, 0, b, 2, data.Length);
            Send(b);
        }

        static int FreePort()
        {
            var l = new TcpListener(IPAddress.Loopback, 0);
            l.Start();
            int p = ((IPEndPoint)l.LocalEndpoint).Port;
            l.Stop();
            return p;
        }
    }

    // Fallback: one persistent "adb shell"; queued key presses are batched into a single
    // "input keyevent a b c" so a burst of presses costs one command instead of many.
    sealed class ShellChannel : IChannel
    {
        const string Marker = "__FR_OK__";

        sealed class Cmd
        {
            public char Kind;   // k = key, t = text, c = raw command
            public int Code;
            public string Text;
        }

        readonly BlockingCollection<Cmd> queue = new BlockingCollection<Cmd>(1024);
        readonly AutoResetEvent done = new AutoResetEvent(false);
        Process proc;
        Stream stdin;
        volatile bool closing;
        int failed;

        public event Action<string> Lost;
        public bool Instant { get { return false; } }

        public static ShellChannel Open(AdbTool adb, string serial, out string error)
        {
            error = null;
            var ch = new ShellChannel();
            ch.proc = new Process();
            ch.proc.StartInfo = Proc.Info(adb.ExePath, "-s " + serial + " shell", true);
            ch.proc.EnableRaisingEvents = true;
            ch.proc.OutputDataReceived += delegate (object s, DataReceivedEventArgs e)
            {
                if (e.Data != null && e.Data.Trim() == Marker) ch.done.Set();
            };
            ch.proc.ErrorDataReceived += delegate (object s, DataReceivedEventArgs e) { };
            ch.proc.Exited += delegate { ch.Fail("The TV shell closed"); };
            try { ch.proc.Start(); }
            catch (Exception ex) { error = ex.Message; return null; }
            ch.stdin = ch.proc.StandardInput.BaseStream;
            ch.proc.BeginOutputReadLine();
            ch.proc.BeginErrorReadLine();
            if (!ch.Exec("true", 10000))
            {
                error = "adb shell did not respond";
                ch.Close(false);
                return null;
            }
            var t = new Thread(ch.Loop);
            t.IsBackground = true;
            t.Name = "fr-shell";
            t.Start();
            return ch;
        }

        bool Exec(string cmd, int timeoutMs)
        {
            try
            {
                // "\n" only: a Windows "\r\n" would end up inside the command on the TV.
                byte[] line = Encoding.UTF8.GetBytes(cmd + " ; echo " + Marker + "\n");
                done.Reset();
                stdin.Write(line, 0, line.Length);
                stdin.Flush();
                return done.WaitOne(timeoutMs);
            }
            catch { return false; }
        }

        void Loop()
        {
            Cmd carry = null;
            try
            {
                while (!closing)
                {
                    Cmd c = carry ?? queue.Take();
                    carry = null;
                    string cmd;
                    Cmd next;
                    if (c.Kind == 'k')
                    {
                        var codes = new List<int>();
                        codes.Add(c.Code);
                        while (codes.Count < 16 && queue.TryTake(out next))
                        {
                            if (next.Kind == 'k') codes.Add(next.Code);
                            else { carry = next; break; }
                        }
                        cmd = "input keyevent " + string.Join(" ", codes.Select(x => x.ToString(CultureInfo.InvariantCulture)).ToArray());
                    }
                    else if (c.Kind == 't')
                    {
                        var sb = new StringBuilder(c.Text);
                        while (sb.Length < 200 && queue.TryTake(out next))
                        {
                            if (next.Kind == 't') sb.Append(next.Text);
                            else { carry = next; break; }
                        }
                        cmd = "input text " + Quote(sb.ToString().Replace(" ", "%s"));
                    }
                    else
                    {
                        cmd = c.Text;
                    }
                    if (!Exec(cmd, 15000) && !closing)
                    {
                        Fail("The TV stopped responding");
                        return;
                    }
                }
            }
            catch (InvalidOperationException) { }   // also covers ObjectDisposedException
        }

        static string Quote(string s)
        {
            return "'" + s.Replace("'", "'\\''") + "'";
        }

        void Add(Cmd c)
        {
            if (closing || failed != 0) return;
            try { queue.TryAdd(c); } catch (InvalidOperationException) { }
        }

        public void Key(int keycode) { Add(new Cmd { Kind = 'k', Code = keycode }); }

        public void Text(string text)
        {
            if (!string.IsNullOrEmpty(text)) Add(new Cmd { Kind = 't', Text = text });
        }

        public void StartApp(string package)
        {
            if (string.IsNullOrEmpty(package) || !Regex.IsMatch(package, @"^[A-Za-z0-9._]+$")) return;
            Add(new Cmd { Kind = 'c', Text = "monkey -p " + package + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1" });
        }

        void Fail(string why)
        {
            if (closing) return;
            if (Interlocked.Exchange(ref failed, 1) == 1) return;
            Shutdown();
            var h = Lost;
            if (h != null) h(why);
        }

        public void Close(bool wait)
        {
            closing = true;
            Shutdown();
        }

        void Shutdown()
        {
            try { queue.CompleteAdding(); } catch { }
            try { done.Set(); } catch { }
            try
            {
                if (stdin != null)
                {
                    var bye = Encoding.ASCII.GetBytes("exit\n");
                    stdin.Write(bye, 0, bye.Length);
                    stdin.Flush();
                }
            }
            catch { }
            try { if (proc != null && !proc.WaitForExit(300)) proc.Kill(); } catch { }
        }
    }

    // ------------------------------------------------------------------ session (connection logic)

    enum Link { NoAdb, Idle, Busy, Online }

    sealed class AppEntry
    {
        public string Name;
        public string Package;
        public bool IsSystem;

        // Parses the output of scrcpy-server's list_apps:  " * Name<padding> package" (system) / " - ..." (user).
        // Names of 30+ characters put the package on the next line.
        public static List<AppEntry> ParseScrcpyList(string text)
        {
            var list = new List<AppEntry>();
            var lines = (text ?? "").Replace("\r", "").Split('\n');
            int start = Array.FindIndex(lines, l => l.Contains("List of apps:"));
            if (start < 0) return list;
            var full = new Regex(@"^ ([*-]) (.*?)\s{2,}(\S+)\s*$");
            var nameOnly = new Regex(@"^ ([*-]) (.+?)\s*$");
            for (int i = start + 1; i < lines.Length; i++)
            {
                string l = lines[i];
                var m = full.Match(l);
                if (m.Success)
                {
                    list.Add(new AppEntry { IsSystem = m.Groups[1].Value == "*", Name = m.Groups[2].Value.Trim(), Package = m.Groups[3].Value });
                    continue;
                }
                var n = nameOnly.Match(l);
                if (n.Success && i + 1 < lines.Length)
                {
                    string next = lines[i + 1].Trim();
                    if (next.Length > 0 && next.IndexOf(' ') < 0)
                    {
                        list.Add(new AppEntry { IsSystem = n.Groups[1].Value == "*", Name = n.Groups[2].Value.Trim(), Package = next });
                        i++;
                        continue;
                    }
                }
                if (l.StartsWith("[server]")) break;
            }
            return list;
        }
    }

    sealed class Session
    {
        const string NoAdbText = "adb.exe not found \u2014 click here to locate it";

        readonly Settings settings;
        readonly BlockingCollection<Action> jobs = new BlockingCollection<Action>();
        volatile IChannel channel;
        volatile bool userStopped;
        List<string> serverFiles;

        public volatile AdbTool Adb;
        public volatile string Serial;
        public volatile string Ip;
        public volatile ServerInfo Server;
        public volatile string Why;      // why instant mode isn't available
        public volatile Link State = Link.Idle;
        public event Action<Link, string> Changed;
        public event Action MouseStopped;   // mouse-mode scrcpy ended by itself (raised on a pool thread)
        Process mouseProc;

        public Session(Settings settings)
        {
            this.settings = settings;
            var t = new Thread(delegate ()
            {
                foreach (var job in jobs.GetConsumingEnumerable())
                {
                    try { job(); }
                    catch (Exception ex) { Set(Link.Idle, "Error: " + ex.Message); }
                }
            });
            t.IsBackground = true;
            t.Name = "fr-session";
            t.Start();
        }

        public bool Online { get { return channel != null && State == Link.Online; } }
        public bool Instant { get { var c = channel; return c != null && c.Instant; } }

        void Post(Action a)
        {
            try { jobs.Add(a); } catch (InvalidOperationException) { }
        }

        void Set(Link state, string message)
        {
            State = state;
            var h = Changed;
            if (h != null) h(state, message);
        }

        // ---- public commands (all connection work happens on the session thread)

        public void Start()
        {
            Post(delegate
            {
                Set(Link.Busy, "Looking for adb\u2026");
                string path = Tools.FindAdb(settings.AdbPath);
                if (path == null)
                {
                    Set(Link.NoAdb, NoAdbText);
                    return;
                }
                UseAdb(path);
                AutoConnect();
            });
        }

        public void SetAdb(string path)
        {
            Post(delegate
            {
                UseAdb(path);
                AutoConnect();
            });
        }

        public void Connect(string target)
        {
            Post(delegate
            {
                if (Adb == null) { Set(Link.NoAdb, NoAdbText); return; }
                userStopped = false;
                DoConnect(target, false);
            });
        }

        public void Find()
        {
            Post(delegate
            {
                if (Adb == null) { Set(Link.NoAdb, NoAdbText); return; }
                userStopped = false;
                DoFind(Ip ?? settings.Ip);
            });
        }

        public void Cancel()
        {
            userStopped = true;
            Post(delegate
            {
                StopChannel(true);
                Set(Link.Idle, "Cancelled");
            });
        }

        public void Disconnect()
        {
            userStopped = true;
            Post(delegate
            {
                StopChannel(true);
                Set(Link.Idle, "Disconnected");
            });
        }

        public void Shutdown()
        {
            userStopped = true;
            StopMouse();
            StopChannel(true);
            try { jobs.CompleteAdding(); } catch { }
        }

        public void Key(int keycode)
        {
            var c = channel;
            if (c != null) c.Key(keycode);
        }

        public void Text(string text)
        {
            var c = channel;
            if (c != null && !string.IsNullOrEmpty(text)) c.Text(text);
        }

        public void LaunchApp(string package)
        {
            var c = channel;
            if (c != null) c.StartApp(package);
        }

        // Long presses need FLAG_LONG_PRESS, which "input keyevent --longpress" sets; latency doesn't matter here.
        public void LongPress(int keycode)
        {
            var adb = Adb;
            var serial = Serial;
            if (adb == null || serial == null || channel == null) return;
            ThreadPool.QueueUserWorkItem(delegate
            {
                adb.On(serial, "shell input keyevent --longpress " + keycode.ToString(CultureInfo.InvariantCulture), 15000);
            });
        }

        public void OpenSettings()
        {
            var adb = Adb;
            var serial = Serial;
            if (adb == null || serial == null || channel == null) return;
            ThreadPool.QueueUserWorkItem(delegate { adb.On(serial, "shell am start -a android.settings.SETTINGS", 15000); });
        }

        // Website tiles keep their address in AppEntry.Package and open in Sofa TV Browser.
        public const string BrowserPackage = "app.sofabrowser";

        public static bool IsSite(string target)
        {
            return target != null && (target.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
                || target.StartsWith("https://", StringComparison.OrdinalIgnoreCase));
        }

        // Returns an escaped http(s) address that is safe inside the adb shell command, or null if it isn't one.
        public static string CleanSite(string text)
        {
            string t = (text ?? "").Trim();
            if (t.Length == 0) return null;
            if (t.IndexOf("://", StringComparison.Ordinal) < 0) t = "https://" + t;
            Uri u;
            if (!Uri.TryCreate(t, UriKind.Absolute, out u)) return null;
            if (u.Scheme != Uri.UriSchemeHttp && u.Scheme != Uri.UriSchemeHttps) return null;
            if (u.Host.IndexOf('.') < 0) return null;
            return u.AbsoluteUri.Replace("'", "%27");
        }

        public void OpenSite(string address, Action<string> failed)
        {
            var adb = Adb;
            var serial = Serial;
            string url = CleanSite(address);
            if (adb == null || serial == null || url == null) return;
            ThreadPool.QueueUserWorkItem(delegate
            {
                var r = adb.On(serial, "shell am start -a android.intent.action.VIEW -d '" + url + "' -p " + BrowserPackage, 15000);
                if (r.TimedOut || Regex.IsMatch(r.All, "(?m)^\\s*Error")) failed("Couldn't open it \u2014 is Sofa TV Browser installed?");
            });
        }

        public void LoadApps(Action<List<AppEntry>> done)
        {
            var adb = Adb;
            var serial = Serial;
            var server = Server;
            if (adb == null || serial == null)
            {
                done(null);
                return;
            }
            ThreadPool.QueueUserWorkItem(delegate
            {
                List<AppEntry> apps = null;
                try
                {
                    if (server != null)
                    {
                        // A separate short-lived server run that prints app names + packages, then exits.
                        adb.On(serial, "push \"" + server.Path + "\" " + FastChannel.DeviceJar, 90000);
                        var r = adb.On(serial, "shell CLASSPATH=" + FastChannel.DeviceJar + " app_process / com.genymobile.scrcpy.Server "
                            + server.Version + " list_apps=true log_level=info cleanup=false", 120000);
                        adb.On(serial, "shell rm -f " + FastChannel.DeviceJar, 10000);
                        apps = AppEntry.ParseScrcpyList(r.Out + "\n" + r.Err);
                    }
                    if (apps == null || apps.Count == 0)
                    {
                        var r = adb.On(serial, "shell pm list packages -3", 30000);
                        apps = r.Out.Replace("\r", "").Split('\n')
                            .Select(l => l.Trim())
                            .Where(l => l.StartsWith("package:"))
                            .Select(l => new AppEntry { Name = l.Substring(8), Package = l.Substring(8) })
                            .ToList();
                    }
                }
                catch { }
                done(apps);
            });
        }

        public bool StartMirror(out string error)
        {
            error = null;
            var adb = Adb;
            var serial = Serial;
            if (adb == null || serial == null)
            {
                error = "Not connected to the TV.";
                return false;
            }
            var server = Server;
            string exe = Tools.FindScrcpy(server != null ? server.Path : null, adb.ExePath);
            if (exe == null)
            {
                error = "scrcpy.exe wasn't found next to adb.exe, so the TV screen can't be shown.";
                return false;
            }
            try
            {
                var psi = Proc.Info(exe, "-s " + serial + " --no-audio --max-size=1280 --video-bit-rate=4M --window-title=\"Fire TV screen\"", false);
                psi.EnvironmentVariables["ADB"] = adb.ExePath;
                Process.Start(psi);
                return true;
            }
            catch (Exception ex)
            {
                error = ex.Message;
                return false;
            }
        }

        // Mouse mode: a separate scrcpy that simulates a physical HID mouse on the TV (no video, no audio).
        public bool StartMouse(out string error)
        {
            error = null;
            var adb = Adb;
            var serial = Serial;
            if (adb == null || serial == null)
            {
                error = "Not connected to the TV.";
                return false;
            }
            var server = Server;
            string exe = Tools.FindScrcpy(server != null ? server.Path : null, adb.ExePath);
            if (exe == null)
            {
                error = "scrcpy.exe wasn't found next to adb.exe, so the TV mouse can't be used.";
                return false;
            }
            try
            {
                var psi = Proc.Info(exe, "-s " + serial + " --no-video --no-audio -M", false);
                psi.EnvironmentVariables["ADB"] = adb.ExePath;
                var p = new Process();
                p.StartInfo = psi;
                p.EnableRaisingEvents = true;
                p.Exited += delegate
                {
                    if (Interlocked.CompareExchange(ref mouseProc, null, p) != p) return;   // StopMouse owns it
                    p.Dispose();
                    var h = MouseStopped;
                    if (h != null) h();
                };
                mouseProc = p;
                try { p.Start(); }
                catch
                {
                    mouseProc = null;
                    p.Dispose();
                    throw;
                }
                return true;
            }
            catch (Exception ex)
            {
                error = ex.Message;
                return false;
            }
        }

        public void StopMouse()
        {
            var p = Interlocked.Exchange(ref mouseProc, null);
            if (p == null) return;
            try
            {
                p.Refresh();
                if (p.HasExited) return;
                if (!p.CloseMainWindow() || !p.WaitForExit(1500)) p.Kill();
            }
            catch { }
            finally { p.Dispose(); }
        }

        // ---- worker-side logic

        void UseAdb(string path)
        {
            Adb = new AdbTool(path);
            serverFiles = null;
            Server = null;
            settings.AdbPath = path;
            settings.Save();
            Set(Link.Busy, "Starting adb\u2026");
            Proc.RunQuiet(path, "start-server", 20000);
        }

        void AutoConnect()
        {
            userStopped = false;
            if (!string.IsNullOrEmpty(settings.Ip)) DoConnect(settings.Ip, true);
            else DoFind(null);
        }

        void StopChannel(bool wait)
        {
            var c = channel;
            channel = null;
            if (c != null) c.Close(wait);
        }

        // True when adb has a link to the TV, possibly still waiting for the user to allow this PC.
        bool AdbConnect(string serial)
        {
            var r = Adb.Run("connect " + serial, 20000);
            string all = r.All.ToLowerInvariant();
            if (all.Contains("authenticate")) return true;   // "failed to authenticate": the TV shows the Allow prompt
            return all.Contains("connected to") && !all.Contains("failed") && !all.Contains("cannot") && !all.Contains("unable");
        }

        bool DoConnect(string target, bool findIfFails)
        {
            target = (target ?? "").Trim();
            if (target.Length == 0) return DoFind(null);
            string serial = target.IndexOf(':') < 0 ? target + ":5555" : target;
            string ip = serial.Substring(0, serial.LastIndexOf(':'));
            StopChannel(false);
            Set(Link.Busy, "Connecting to " + ip + "\u2026");
            if (!AdbConnect(serial))
            {
                if (findIfFails && DoFind(ip)) return true;
                if (!userStopped) Set(Link.Idle, "Can't reach " + ip + " \u2014 is ADB debugging on?");
                return false;
            }
            return Attach(serial);
        }

        string DeviceState(string serial)
        {
            var r = Adb.On(serial, "get-state", 10000);
            if (r.Out.Trim() == "device") return "device";
            string all = r.All.ToLowerInvariant();
            if (all.Contains("unauthorized") || all.Contains("authorizing")) return "unauthorized";
            if (all.Contains("offline")) return "offline";
            if (all.Contains("not found")) return "missing";
            return all.Length == 0 ? "no answer" : FastChannel.LastLine(r.All);
        }

        bool Attach(string serial)
        {
            string ip = serial.Substring(0, serial.LastIndexOf(':'));
            DateTime until = DateTime.UtcNow.AddSeconds(90);
            int tick = 0;
            while (true)
            {
                string st = DeviceState(serial);
                if (st == "device") break;
                if (userStopped)
                {
                    Set(Link.Idle, "Disconnected");
                    return false;
                }
                if (DateTime.UtcNow > until)
                {
                    Set(Link.Idle, st == "unauthorized"
                        ? "Not allowed on the TV \u2014 press Connect to retry"
                        : "The TV doesn't answer (" + st + ")");
                    return false;
                }
                if (st == "unauthorized")
                {
                    Set(Link.Busy, "On the TV: tick \u201CAlways allow\u201D, then OK");
                }
                else
                {
                    Set(Link.Busy, "Waiting for the TV \u2014 if it asks, tick \u201CAlways allow\u201D");
                    if (st == "missing" && tick % 3 == 0) AdbConnect(serial);
                }
                tick++;
                Thread.Sleep(1000);
            }

            Serial = serial;
            Ip = ip;
            settings.Ip = ip;
            settings.Save();

            Set(Link.Busy, "Starting instant mode\u2026");
            string why;
            IChannel ch = OpenFast(out why);
            Why = ch == null ? why : null;
            if (ch == null)
            {
                string err;
                ch = ShellChannel.Open(Adb, serial, out err);
                if (ch == null)
                {
                    Set(Link.Idle, "The TV shell failed: " + err);
                    return false;
                }
            }
            IChannel mine = ch;
            ch.Lost += delegate (string reason) { OnLost(mine, reason); };
            channel = ch;
            Set(Link.Online, ch.Instant ? "Connected \u00B7 instant" : "Connected \u00B7 basic mode (slower)");
            return true;
        }

        IChannel OpenFast(out string why)
        {
            why = "scrcpy-server wasn't found next to adb.exe";
            if (serverFiles == null) serverFiles = Tools.FindServers(Adb.ExePath);
            var tries = new List<ServerInfo>();
            var known = Server;
            if (known != null) tries.Add(known);
            foreach (var f in serverFiles)
                if (known == null || !string.Equals(f, known.Path, StringComparison.OrdinalIgnoreCase))
                    tries.Add(new ServerInfo { Path = f });
            foreach (var info in tries.Take(2))
            {
                if (info.Version == null) info.Version = Tools.GuessServerVersion(info.Path);
                string err;
                var ch = FastChannel.Open(Adb, Serial, info, out err);
                if (ch != null)
                {
                    Server = info;
                    return ch;
                }
                why = err;
            }
            return null;
        }

        void OnLost(IChannel lost, string reason)
        {
            Post(delegate
            {
                if (channel != lost) return;
                channel = null;
                if (userStopped) return;
                string ip = Ip;
                for (int i = 1; i <= 3 && !userStopped; i++)
                {
                    Set(Link.Busy, reason + " \u2014 reconnecting (" + i + "/3)\u2026");
                    Thread.Sleep(1500);
                    if (userStopped) break;
                    if (DoConnect(ip, false)) return;
                }
                if (!userStopped) Set(Link.Idle, "Disconnected \u2014 press Connect to retry");
            });
        }

        bool IsAmazon(string serial)
        {
            var r = Adb.On(serial, "shell getprop ro.product.manufacturer", 8000);
            return r.Out.IndexOf("amazon", StringComparison.OrdinalIgnoreCase) >= 0;
        }

        bool DoFind(string hintIp)
        {
            StopChannel(false);
            Set(Link.Busy, "Looking for your Fire TV\u2026");
            var prefixes = Net.ScanPrefixes(hintIp);

            // 1. Network devices adb already knows (Amazon first, then ones on the hotspot).
            var known = new List<string>();
            var dev = Adb.Run("devices", 10000);
            foreach (var raw in dev.Out.Replace("\r", "").Split('\n'))
            {
                var parts = raw.Split(new[] { '\t', ' ' }, StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length >= 2 && parts[1] == "device" && Regex.IsMatch(parts[0], @"^\d+\.\d+\.\d+\.\d+:\d+$"))
                    known.Add(parts[0]);
            }
            foreach (var serial in known
                .OrderBy(sr => IsAmazon(sr) ? 0 : 1)
                .ThenBy(sr => prefixes.Any(p => sr.StartsWith(p + ".", StringComparison.Ordinal)) ? 0 : 1))
            {
                if (userStopped) return false;
                if (Attach(serial)) return true;
            }

            // 2. Sweep the hotspot for an open ADB port.
            if (prefixes.Count == 0)
            {
                Set(Link.Idle, "No hotspot found \u2014 type the TV's IP");
                return false;
            }
            Set(Link.Busy, "Scanning " + string.Join(", ", prefixes.Select(p => p + ".x").ToArray()) + "\u2026");
            var hits = Net.Scan(prefixes, 5555, 1200);
            if (hintIp != null) hits = hits.OrderBy(h => h == hintIp ? 0 : 1).ToList();
            foreach (var hostIp in hits)
            {
                if (userStopped) return false;
                string serial = hostIp + ":5555";
                Set(Link.Busy, "Found " + hostIp + " \u2014 connecting\u2026");
                if (AdbConnect(serial) && Attach(serial)) return true;
            }
            if (!userStopped)
                Set(Link.Idle, hits.Count == 0 ? "No Fire TV found \u2014 is ADB debugging on?" : "Found a device, but it refused ADB");
            return false;
        }
    }

    // ------------------------------------------------------------------ look & feel

    static class Theme
    {
        public static readonly Color Bg = Hex(0x111214);
        public static readonly Color Surface = Hex(0x1F2125);
        public static readonly Color SurfaceHover = Hex(0x292C31);
        public static readonly Color SurfacePressed = Hex(0x363A41);
        public static readonly Color Ring = Hex(0x1B1D21);
        public static readonly Color RingEdge = Hex(0x272A2F);
        public static readonly Color RingHover = Hex(0x25282D);
        public static readonly Color RingPressed = Hex(0x33373E);
        public static readonly Color OkFill = Hex(0x26292E);
        public static readonly Color OkHover = Hex(0x2F3339);
        public static readonly Color OkEdge = Hex(0x3B3F47);
        public static readonly Color Accent = Hex(0xFF9900);
        public static readonly Color AccentText = Hex(0x1C1300);
        public static readonly Color Icon = Hex(0xE7E9EC);
        public static readonly Color IconDim = Hex(0x5A5E66);
        public static readonly Color Text = Hex(0xE7E9EC);
        public static readonly Color SubText = Hex(0x8C9199);
        public static readonly Color Field = Hex(0x1A1C20);
        public static readonly Color FieldEdge = Hex(0x2C2F35);
        public static readonly Color FieldFocus = Hex(0x575C66);
        public static readonly Color Menu = Hex(0x1B1D21);
        public static readonly Color MenuHot = Hex(0x2C2F36);
        public static readonly Color MenuLine = Hex(0x30333A);
        public static readonly Color Green = Hex(0x3DDC84);
        public static readonly Color Amber = Hex(0xF5A524);
        public static readonly Color Red = Hex(0xF4525B);
        public static readonly Color Blue = Hex(0x5B9BFF);
        public static readonly Color Gray = Hex(0x6B7078);

        static Color Hex(int v) { return Color.FromArgb(255, (v >> 16) & 255, (v >> 8) & 255, v & 255); }

        public static Color Mix(Color a, Color b, float t)
        {
            return Color.FromArgb(255,
                (int)(a.R + (b.R - a.R) * t), (int)(a.G + (b.G - a.G) * t), (int)(a.B + (b.B - a.B) * t));
        }

        public static Font UiFont(float size, FontStyle style)
        {
            return new Font("Segoe UI", size, style);
        }

        public static Font SemiboldFont(float size)
        {
            var f = new Font("Segoe UI Semibold", size);
            if (f.Name != "Segoe UI Semibold")
            {
                f.Dispose();
                f = new Font("Segoe UI", size, FontStyle.Bold);
            }
            return f;
        }
    }

    static class Draw
    {
        public static GraphicsPath RoundRect(RectangleF r, float radius)
        {
            var p = new GraphicsPath();
            float d = Math.Min(radius * 2f, Math.Min(r.Width, r.Height));
            if (d <= 0.5f)
            {
                p.AddRectangle(r);
                return p;
            }
            p.AddArc(r.X, r.Y, d, d, 180, 90);
            p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
            p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
            p.CloseFigure();
            return p;
        }

        public static Pen RoundPen(Color c, float width)
        {
            var p = new Pen(c, width);
            p.StartCap = LineCap.Round;
            p.EndCap = LineCap.Round;
            p.LineJoin = LineJoin.Round;
            return p;
        }

        // A chevron centred on p, pointing at angleDeg (GDI+ angles: 0 = right, 90 = down).
        public static void Chevron(Graphics g, PointF p, float size, float angleDeg, Color color, float width)
        {
            double a = angleDeg * Math.PI / 180.0;
            float dx = (float)Math.Cos(a), dy = (float)Math.Sin(a);
            var tip = new PointF(p.X + dx * size * 0.35f, p.Y + dy * size * 0.35f);
            var back = new PointF(p.X - dx * size * 0.35f, p.Y - dy * size * 0.35f);
            var w1 = new PointF(back.X - dy * size * 0.7f, back.Y + dx * size * 0.7f);
            var w2 = new PointF(back.X + dy * size * 0.7f, back.Y - dx * size * 0.7f);
            using (var pen = RoundPen(color, width)) g.DrawLines(pen, new[] { w1, tip, w2 });
        }
    }

    delegate void Glyph(Graphics g, RectangleF box, Color color);

    // Vector icons drawn on a 24x24 grid, so they stay sharp at any DPI and need no icon font.
    static class Glyphs
    {
        static GraphicsState Begin(Graphics g, RectangleF b)
        {
            var st = g.Save();
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TranslateTransform(b.X, b.Y);
            g.ScaleTransform(b.Width / 24f, b.Height / 24f);
            return st;
        }

        static PointF P(float x, float y) { return new PointF(x, y); }

        static void FillSoft(Graphics g, Color c, PointF[] pts)
        {
            using (var path = new GraphicsPath())
            using (var br = new SolidBrush(c))
            using (var pen = Draw.RoundPen(c, 1.3f))
            {
                path.AddPolygon(pts);
                g.FillPath(br, path);
                g.DrawPath(pen, path);
            }
        }

        public static void Back(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            using (var p = Draw.RoundPen(c, 1.9f))
            using (var path = new GraphicsPath())
            {
                path.AddLine(6.5f, 7.5f, 14.5f, 7.5f);
                path.AddArc(10f, 7.5f, 9f, 9f, -90f, 180f);
                path.AddLine(14.5f, 16.5f, 7f, 16.5f);
                g.DrawPath(p, path);
                g.DrawLines(p, new[] { P(10f, 4f), P(6.5f, 7.5f), P(10f, 11f) });
            }
            g.Restore(st);
        }

        public static void Home(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            using (var p = Draw.RoundPen(c, 1.9f))
            {
                g.DrawLines(p, new[] { P(3.5f, 11.5f), P(12f, 4f), P(20.5f, 11.5f) });
                g.DrawLines(p, new[] { P(6f, 9.5f), P(6f, 19.5f), P(18f, 19.5f), P(18f, 9.5f) });
                g.DrawLines(p, new[] { P(10.2f, 19.5f), P(10.2f, 14.5f), P(13.8f, 14.5f), P(13.8f, 19.5f) });
            }
            g.Restore(st);
        }

        public static void Menu(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            using (var p = Draw.RoundPen(c, 1.9f))
            {
                g.DrawLine(p, 5f, 7f, 19f, 7f);
                g.DrawLine(p, 5f, 12f, 19f, 12f);
                g.DrawLine(p, 5f, 17f, 19f, 17f);
            }
            g.Restore(st);
        }

        public static void PlayPause(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            FillSoft(g, c, new[] { P(3.5f, 6f), P(3.5f, 18f), P(12.5f, 12f) });
            using (var br = new SolidBrush(c))
            {
                using (var bar = Draw.RoundRect(new RectangleF(14.5f, 6f, 2.8f, 12f), 0.9f)) g.FillPath(br, bar);
                using (var bar = Draw.RoundRect(new RectangleF(19f, 6f, 2.8f, 12f), 0.9f)) g.FillPath(br, bar);
            }
            g.Restore(st);
        }

        public static void Rewind(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            FillSoft(g, c, new[] { P(12f, 6.5f), P(12f, 17.5f), P(3.5f, 12f) });
            FillSoft(g, c, new[] { P(20.5f, 6.5f), P(20.5f, 17.5f), P(12f, 12f) });
            g.Restore(st);
        }

        public static void Forward(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            FillSoft(g, c, new[] { P(3.5f, 6.5f), P(3.5f, 17.5f), P(12f, 12f) });
            FillSoft(g, c, new[] { P(12f, 6.5f), P(12f, 17.5f), P(20.5f, 12f) });
            g.Restore(st);
        }

        static void Speaker(Graphics g, Color c)
        {
            FillSoft(g, c, new[] { P(3f, 9.5f), P(6.8f, 9.5f), P(11.5f, 5.3f), P(11.5f, 18.7f), P(6.8f, 14.5f), P(3f, 14.5f) });
        }

        public static void Mute(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            Speaker(g, c);
            using (var p = Draw.RoundPen(c, 1.9f))
            {
                g.DrawLine(p, 15f, 9.5f, 20.5f, 15f);
                g.DrawLine(p, 20.5f, 9.5f, 15f, 15f);
            }
            g.Restore(st);
        }

        public static void VolumeDown(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            Speaker(g, c);
            using (var p = Draw.RoundPen(c, 1.9f)) g.DrawLine(p, 15f, 12f, 21f, 12f);
            g.Restore(st);
        }

        public static void VolumeUp(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            Speaker(g, c);
            using (var p = Draw.RoundPen(c, 1.9f))
            {
                g.DrawLine(p, 15f, 12f, 21f, 12f);
                g.DrawLine(p, 18f, 9f, 18f, 15f);
            }
            g.Restore(st);
        }

        public static void Power(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            using (var p = Draw.RoundPen(c, 1.9f))
            {
                g.DrawArc(p, 4.5f, 5f, 15f, 15f, -55f, 290f);
                g.DrawLine(p, 12f, 3.2f, 12f, 11.5f);
            }
            g.Restore(st);
        }

        public static void Gear(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            using (var path = new GraphicsPath(FillMode.Alternate))
            using (var br = new SolidBrush(c))
            {
                var pts = new List<PointF>();
                const int teeth = 8;
                const float ro = 10.2f, ri = 7.7f;
                double half = Math.PI / teeth * 0.5, ramp = Math.PI / teeth * 0.28;
                for (int i = 0; i < teeth; i++)
                {
                    double a = i * 2 * Math.PI / teeth;
                    pts.Add(Polar(a - half - ramp, ri));
                    pts.Add(Polar(a - half, ro));
                    pts.Add(Polar(a + half, ro));
                    pts.Add(Polar(a + half + ramp, ri));
                }
                path.AddPolygon(pts.ToArray());
                path.AddEllipse(12f - 3.3f, 12f - 3.3f, 6.6f, 6.6f);
                g.FillPath(br, path);
            }
            g.Restore(st);
        }

        static PointF Polar(double a, float r)
        {
            return new PointF(12f + (float)(Math.Cos(a) * r), 12f + (float)(Math.Sin(a) * r));
        }

        public static void Apps(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            using (var br = new SolidBrush(c))
            {
                foreach (var pt in new[] { P(4f, 4f), P(13f, 4f), P(4f, 13f), P(13f, 13f) })
                    using (var r = Draw.RoundRect(new RectangleF(pt.X, pt.Y, 7f, 7f), 1.8f)) g.FillPath(br, r);
            }
            g.Restore(st);
        }

        public static void Tv(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            using (var p = Draw.RoundPen(c, 1.9f))
            {
                using (var r = Draw.RoundRect(new RectangleF(3f, 4.5f, 18f, 12f), 1.8f)) g.DrawPath(p, r);
                g.DrawLine(p, 12f, 16.5f, 12f, 19.5f);
                g.DrawLine(p, 8f, 19.8f, 16f, 19.8f);
            }
            g.Restore(st);
        }

        public static void Mouse(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            using (var p = Draw.RoundPen(c, 1.9f))
            {
                using (var r = Draw.RoundRect(new RectangleF(6.5f, 3f, 11f, 18f), 5.5f)) g.DrawPath(p, r);
                g.DrawLine(p, 12f, 7f, 12f, 10.5f);
            }
            g.Restore(st);
        }

        public static void Search(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            using (var p = Draw.RoundPen(c, 2f))
            {
                g.DrawEllipse(p, 4f, 4f, 11.5f, 11.5f);
                g.DrawLine(p, 14f, 14f, 19.5f, 19.5f);
            }
            g.Restore(st);
        }

        public static void More(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            using (var br = new SolidBrush(c))
                foreach (float x in new[] { 5.5f, 12f, 18.5f })
                    g.FillEllipse(br, x - 1.7f, 12f - 1.7f, 3.4f, 3.4f);
            g.Restore(st);
        }

        public static void Keyboard(Graphics g, RectangleF b, Color c)
        {
            var st = Begin(g, b);
            using (var p = Draw.RoundPen(c, 1.7f))
            using (var br = new SolidBrush(c))
            {
                using (var r = Draw.RoundRect(new RectangleF(2.5f, 6f, 19f, 12.5f), 2.2f)) g.DrawPath(p, r);
                foreach (float x in new[] { 6.5f, 10f, 13.5f, 17f }) g.FillEllipse(br, x - 0.95f, 9.3f - 0.95f, 1.9f, 1.9f);
                foreach (float x in new[] { 8.25f, 11.75f, 15.25f }) g.FillEllipse(br, x - 0.95f, 12.2f - 0.95f, 1.9f, 1.9f);
                g.DrawLine(p, 8.5f, 15.4f, 15.5f, 15.4f);
            }
            g.Restore(st);
        }

        public static void Pin(Graphics g, RectangleF b, Color c, bool filled)
        {
            var st = Begin(g, b);
            g.TranslateTransform(12f, 12f);
            g.RotateTransform(40f);
            g.TranslateTransform(-12f, -12f);
            using (var p = Draw.RoundPen(c, 1.7f))
            using (var path = new GraphicsPath())
            {
                path.AddPolygon(new[] { P(9f, 3.5f), P(15f, 3.5f), P(14f, 9.5f), P(16.8f, 13.5f), P(7.2f, 13.5f), P(10f, 9.5f) });
                if (filled)
                    using (var br = new SolidBrush(c)) g.FillPath(br, path);
                g.DrawPath(p, path);
                g.DrawLine(p, 12f, 13.5f, 12f, 20.5f);
            }
            g.Restore(st);
        }
    }

    sealed class DarkMenuColors : ProfessionalColorTable
    {
        public override Color ToolStripDropDownBackground { get { return Theme.Menu; } }
        public override Color ImageMarginGradientBegin { get { return Theme.Menu; } }
        public override Color ImageMarginGradientMiddle { get { return Theme.Menu; } }
        public override Color ImageMarginGradientEnd { get { return Theme.Menu; } }
        public override Color MenuBorder { get { return Theme.MenuLine; } }
        public override Color MenuItemBorder { get { return Theme.MenuHot; } }
        public override Color MenuItemSelected { get { return Theme.MenuHot; } }
        public override Color MenuItemSelectedGradientBegin { get { return Theme.MenuHot; } }
        public override Color MenuItemSelectedGradientEnd { get { return Theme.MenuHot; } }
        public override Color MenuItemPressedGradientBegin { get { return Theme.MenuHot; } }
        public override Color MenuItemPressedGradientEnd { get { return Theme.MenuHot; } }
        public override Color SeparatorDark { get { return Theme.MenuLine; } }
        public override Color SeparatorLight { get { return Theme.Menu; } }
    }

    sealed class DarkMenuRenderer : ToolStripProfessionalRenderer
    {
        public DarkMenuRenderer() : base(new DarkMenuColors()) { RoundedEdges = false; }

        protected override void OnRenderArrow(ToolStripArrowRenderEventArgs e)
        {
            e.ArrowColor = Theme.Text;
            base.OnRenderArrow(e);
        }

        protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
        {
            e.TextColor = e.Item.Enabled ? Theme.Text : Theme.SubText;
            base.OnRenderItemText(e);
        }
    }

    // ------------------------------------------------------------------ small controls

    sealed class FlatButton : Control
    {
        bool hover, down;
        public Glyph Art;
        public bool Accent;
        public bool Ghost;
        public float Radius = -1;

        public FlatButton()
        {
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
            SetStyle(ControlStyles.Selectable, false);
            TabStop = false;
            BackColor = Theme.Bg;
            Cursor = Cursors.Hand;
            Font = Theme.SemiboldFont(9f);
        }

        protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { hover = false; down = false; Invalidate(); base.OnMouseLeave(e); }
        protected override void OnMouseDown(MouseEventArgs e)
        {
            if (e.Button == MouseButtons.Left) { down = true; Invalidate(); }
            base.OnMouseDown(e);
        }
        protected override void OnMouseUp(MouseEventArgs e) { down = false; Invalidate(); base.OnMouseUp(e); }
        protected override void OnEnabledChanged(EventArgs e) { Invalidate(); base.OnEnabledChanged(e); }
        protected override void OnTextChanged(EventArgs e) { Invalidate(); base.OnTextChanged(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.Clear(BackColor);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var r = new RectangleF(0.5f, 0.5f, Width - 1f, Height - 1f);
            Color fill;
            if (!Enabled) fill = Ghost ? BackColor : Theme.Surface;
            else if (Accent) fill = down ? Theme.Mix(Theme.Accent, Color.Black, 0.18f) : hover ? Theme.Mix(Theme.Accent, Color.White, 0.14f) : Theme.Accent;
            else if (Ghost) fill = down ? Theme.SurfacePressed : hover ? Theme.SurfaceHover : BackColor;
            else fill = down ? Theme.SurfacePressed : hover ? Theme.SurfaceHover : Theme.Surface;
            using (var path = Draw.RoundRect(r, Radius >= 0 ? Radius : r.Height / 2f))
            using (var br = new SolidBrush(fill))
                g.FillPath(br, path);
            Color fg = !Enabled ? Theme.IconDim : Accent ? Theme.AccentText : Theme.Text;
            if (Art != null && string.IsNullOrEmpty(Text))
            {
                float sz = Math.Min(Width, Height) * 0.56f;
                Art(g, new RectangleF((Width - sz) / 2f, (Height - sz) / 2f, sz, sz), fg);
            }
            else
            {
                TextRenderer.DrawText(g, Text, Font, ClientRectangle, fg,
                    TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine | TextFormatFlags.NoPrefix);
            }
        }
    }

    sealed class FieldBox : Control
    {
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        static extern IntPtr SendMessage(IntPtr hWnd, int msg, IntPtr wParam, string lParam);

        public readonly TextBox Box = new TextBox();
        readonly float s;
        readonly Glyph art;
        string cue;

        public FieldBox(float scale, Glyph art)
        {
            s = scale;
            this.art = art;
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
            BackColor = Theme.Bg;
            Box.BorderStyle = BorderStyle.None;
            Box.BackColor = Theme.Field;
            Box.ForeColor = Theme.Text;
            Box.Font = Theme.UiFont(10f, FontStyle.Regular);
            Box.GotFocus += delegate { Invalidate(); };
            Box.LostFocus += delegate { Invalidate(); };
            Box.HandleCreated += delegate { ApplyCue(); };
            Controls.Add(Box);
            Cursor = Cursors.IBeam;
        }

        public string Cue
        {
            get { return cue; }
            set { cue = value; ApplyCue(); }
        }

        void ApplyCue()
        {
            if (!Tools.IsWindows || !Box.IsHandleCreated) return;
            try { SendMessage(Box.Handle, 0x1501, (IntPtr)1, cue ?? ""); } catch { }   // EM_SETCUEBANNER
        }

        protected override void OnLayout(LayoutEventArgs e)
        {
            base.OnLayout(e);
            int left = art != null ? (int)(36 * s) : (int)(12 * s);
            int h = Box.PreferredHeight;
            Box.SetBounds(left, (Height - h) / 2, Math.Max(10, Width - left - (int)(10 * s)), h);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.Clear(BackColor);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var r = new RectangleF(0.5f, 0.5f, Width - 1f, Height - 1f);
            using (var path = Draw.RoundRect(r, 9 * s))
            {
                using (var br = new SolidBrush(Theme.Field)) g.FillPath(br, path);
                using (var pen = new Pen(Box.Focused ? Theme.FieldFocus : Theme.FieldEdge, 1f)) g.DrawPath(pen, path);
            }
            if (art != null)
            {
                float sz = 18 * s;
                art(g, new RectangleF(11 * s, (Height - sz) / 2f, sz, sz), Box.Focused ? Theme.Text : Theme.SubText);
            }
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            Box.Focus();
            base.OnMouseDown(e);
        }
    }

    sealed class HeaderBar : Control
    {
        readonly float s;
        public readonly FlatButton PinButton = new FlatButton();
        public readonly FlatButton MoreButton = new FlatButton();
        readonly Font linkFont;
        string text = "Starting\u2026";
        Color dot = Theme.Gray;
        bool link, pinned;

        public event Action PinClicked;
        public event Action MoreClicked;
        public event Action StatusClicked;

        public HeaderBar(float scale)
        {
            s = scale;
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.ResizeRedraw, true);
            BackColor = Theme.Bg;
            Font = Theme.UiFont(9f, FontStyle.Regular);
            linkFont = Theme.UiFont(9f, FontStyle.Underline);
            MoreButton.Ghost = true;
            MoreButton.Art = Glyphs.More;
            MoreButton.Radius = 8 * s;
            PinButton.Ghost = true;
            PinButton.Radius = 8 * s;
            PinButton.Art = delegate (Graphics g, RectangleF b, Color c) { Glyphs.Pin(g, b, pinned ? Theme.Accent : c, pinned); };
            MoreButton.Click += delegate { var h = MoreClicked; if (h != null) h(); };
            PinButton.Click += delegate { var h = PinClicked; if (h != null) h(); };
            Controls.Add(MoreButton);
            Controls.Add(PinButton);
        }

        public bool Pinned
        {
            get { return pinned; }
            set { pinned = value; PinButton.Invalidate(); }
        }

        public void SetStatus(string t, Color d, bool clickable)
        {
            text = t ?? "";
            dot = d;
            link = clickable;
            Cursor = clickable ? Cursors.Hand : Cursors.Default;
            Invalidate();
        }

        protected override void OnLayout(LayoutEventArgs e)
        {
            base.OnLayout(e);
            int b = (int)(30 * s), y = (Height - b) / 2;
            MoreButton.Bounds = new Rectangle(Width - (int)(12 * s) - b, y, b, b);
            PinButton.Bounds = new Rectangle(MoreButton.Left - (int)(2 * s) - b, y, b, b);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.Clear(BackColor);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            float cx = 21 * s, cy = Height / 2f, r = 4.5f * s;
            using (var halo = new SolidBrush(Color.FromArgb(55, dot))) g.FillEllipse(halo, cx - r * 2, cy - r * 2, r * 4, r * 4);
            using (var br = new SolidBrush(dot)) g.FillEllipse(br, cx - r, cy - r, r * 2, r * 2);

            int x = (int)(36 * s);
            int w = Math.Max(20, PinButton.Left - x - (int)(4 * s));
            var font = link ? linkFont : Font;
            const TextFormatFlags flags = TextFormatFlags.Left | TextFormatFlags.WordBreak | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPrefix;
            Size size = TextRenderer.MeasureText(g, text, font, new Size(w, int.MaxValue), flags);
            int h = Math.Min(size.Height, Height - (int)(6 * s));
            TextRenderer.DrawText(g, text, font, new Rectangle(x, (Height - h) / 2, w, h), link ? Theme.Blue : Theme.Text, flags);
        }

        protected override void OnMouseClick(MouseEventArgs e)
        {
            base.OnMouseClick(e);
            if (link && e.X < PinButton.Left)
            {
                var h = StatusClicked;
                if (h != null) h();
            }
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) linkFont.Dispose();
            base.Dispose(disposing);
        }
    }

    // ------------------------------------------------------------------ the remote itself

    enum PadShape { Circle, Ring, Ok, Pill }

    sealed class PadButton
    {
        public PadShape Shape;
        public RectangleF Box;
        public float Start;      // ring segments: start angle in GDI+ degrees
        public Glyph Art;
        public string Label;
        public string Tip;
        public int Key = -1;
        public bool Repeat;      // fires repeatedly while held (arrows, volume, seek)
        public bool Hold;        // holding = long-press (OK, Home, Back, Menu)
        public Action Click;     // non-key actions (apps, screen, settings)
        public bool On;          // latched: highlighted while its mode runs (mouse)
        public PointF Center { get { return new PointF(Box.X + Box.Width / 2f, Box.Y + Box.Height / 2f); } }
    }

    sealed class RemotePad : Control
    {
        const int HoldMs = 550;
        readonly float s;
        readonly ToolTip tips;
        readonly List<PadButton> buttons = new List<PadButton>();
        readonly System.Windows.Forms.Timer repeatTimer = new System.Windows.Forms.Timer();
        readonly System.Windows.Forms.Timer holdTimer = new System.Windows.Forms.Timer();
        readonly System.Windows.Forms.Timer flashTimer = new System.Windows.Forms.Timer();
        readonly Font okFont, pillFont;
        PadButton hover, pressed, flash, ok, apps, mouse;
        DateTime pressedAt;
        bool holdFired, pressedInside, live;
        string tipText;
        int wheel;
        PointF center;
        float outerR, innerR, okR;

        public event Action<int> RemoteKey;
        public event Action<int> RemoteLongKey;
        public event Action<string> Notice;
        public event Action TypeRequested;
        public event Action PasteRequested;
        public event Action SettingsClicked;
        public event Action AppsClicked;
        public event Action ScreenClicked;
        public event Action MouseModeClicked;

        public RemotePad(float scale, ToolTip tips)
        {
            s = scale;
            this.tips = tips;
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint
                | ControlStyles.ResizeRedraw | ControlStyles.Selectable, true);
            TabStop = true;
            BackColor = Theme.Bg;
            okFont = Theme.SemiboldFont(11f);
            pillFont = Theme.SemiboldFont(9.5f);
            repeatTimer.Tick += delegate { OnRepeat(); };
            holdTimer.Interval = 30;
            holdTimer.Tick += delegate { OnHoldTick(); };
            flashTimer.Interval = 140;
            flashTimer.Tick += delegate { flashTimer.Stop(); flash = null; Invalidate(); };
            Build();
        }

        public bool Live
        {
            get { return live; }
            set { if (live != value) { live = value; Invalidate(); } }
        }

        public bool MouseOn
        {
            get { return mouse.On; }
            set { if (mouse.On != value) { mouse.On = value; Invalidate(); } }
        }

        public Rectangle AppsRect { get { return Rectangle.Round(apps.Box); } }

        float S(float v) { return v * s; }

        RectangleF Circle(float cx, float cy, float d) { return new RectangleF(S(cx - d / 2), S(cy - d / 2), S(d), S(d)); }

        PadButton Add(PadButton b)
        {
            buttons.Add(b);
            return b;
        }

        void Raise(Action h) { if (h != null) h(); }

        void Build()
        {
            center = new PointF(S(150), S(128));
            outerR = S(98);
            innerR = S(47);
            okR = S(39);
            var ringBox = new RectangleF(center.X - outerR, center.Y - outerR, outerR * 2, outerR * 2);

            Add(new PadButton { Shape = PadShape.Circle, Box = Circle(38, 26, 36), Art = Glyphs.Power, Key = 26, Tip = "Sleep / wake the TV" });
            Add(new PadButton { Shape = PadShape.Circle, Box = Circle(262, 26, 36), Art = Glyphs.Gear, Tip = "Fire TV settings",
                Click = delegate { Raise(SettingsClicked); } });

            Add(new PadButton { Shape = PadShape.Ring, Box = ringBox, Start = 225, Key = 19, Repeat = true, Tip = "Up  (\u2191)" });
            Add(new PadButton { Shape = PadShape.Ring, Box = ringBox, Start = 315, Key = 22, Repeat = true, Tip = "Right  (\u2192)" });
            Add(new PadButton { Shape = PadShape.Ring, Box = ringBox, Start = 45, Key = 20, Repeat = true, Tip = "Down  (\u2193)" });
            Add(new PadButton { Shape = PadShape.Ring, Box = ringBox, Start = 135, Key = 21, Repeat = true, Tip = "Left  (\u2190)" });
            ok = Add(new PadButton { Shape = PadShape.Ok, Box = new RectangleF(center.X - okR, center.Y - okR, okR * 2, okR * 2),
                Key = 23, Hold = true, Tip = "OK  (Enter) \u2014 hold to long-press" });

            Add(new PadButton { Shape = PadShape.Circle, Box = Circle(70, 262, 52), Art = Glyphs.Back, Key = 4, Hold = true, Tip = "Back  (Esc / Backspace)" });
            Add(new PadButton { Shape = PadShape.Circle, Box = Circle(150, 262, 52), Art = Glyphs.Home, Key = 3, Hold = true, Tip = "Home  (H) \u2014 hold for the quick menu" });
            Add(new PadButton { Shape = PadShape.Circle, Box = Circle(230, 262, 52), Art = Glyphs.Menu, Key = 82, Hold = true, Tip = "Menu / options  (M)" });

            Add(new PadButton { Shape = PadShape.Circle, Box = Circle(70, 322, 52), Art = Glyphs.Rewind, Key = 89, Repeat = true, Tip = "Rewind  ([)" });
            Add(new PadButton { Shape = PadShape.Circle, Box = Circle(150, 322, 52), Art = Glyphs.PlayPause, Key = 85, Tip = "Play / pause  (Space)" });
            Add(new PadButton { Shape = PadShape.Circle, Box = Circle(230, 322, 52), Art = Glyphs.Forward, Key = 90, Repeat = true, Tip = "Fast-forward  (])" });

            Add(new PadButton { Shape = PadShape.Circle, Box = Circle(70, 376, 46), Art = Glyphs.Mute, Key = 164, Tip = "Mute (TV volume needs HDMI-CEC)" });
            Add(new PadButton { Shape = PadShape.Circle, Box = Circle(150, 376, 46), Art = Glyphs.VolumeDown, Key = 25, Repeat = true, Tip = "Volume down  (\u2212)" });
            Add(new PadButton { Shape = PadShape.Circle, Box = Circle(230, 376, 46), Art = Glyphs.VolumeUp, Key = 24, Repeat = true, Tip = "Volume up  (+)" });

            apps = Add(new PadButton { Shape = PadShape.Pill, Box = new RectangleF(S(14), S(408), S(85.33f), S(36)), Art = Glyphs.Apps, Label = "Apps",
                Tip = "Open an app on the TV", Click = delegate { Raise(AppsClicked); } });
            Add(new PadButton { Shape = PadShape.Pill, Box = new RectangleF(S(107.33f), S(408), S(85.33f), S(36)), Art = Glyphs.Tv, Label = "Screen",
                Tip = "Show the TV screen here (scrcpy) \u2014 for apps that need a mouse", Click = delegate { Raise(ScreenClicked); } });
            mouse = Add(new PadButton { Shape = PadShape.Pill, Box = new RectangleF(S(200.67f), S(408), S(85.33f), S(36)), Art = Glyphs.Mouse, Label = "Mouse",
                Tip = "Click the scrcpy window to capture the mouse. Alt releases it. Ctrl+Alt+M switches it from any window.", Click = delegate { Raise(MouseModeClicked); } });
        }

        // ---- input

        PadButton HitTest(Point pt)
        {
            float dx = pt.X - center.X, dy = pt.Y - center.Y;
            double d = Math.Sqrt(dx * dx + dy * dy);
            if (d <= okR) return ok;
            if (d >= innerR && d <= outerR)
            {
                double a = Math.Atan2(dy, dx) * 180.0 / Math.PI;
                foreach (var b in buttons)
                {
                    if (b.Shape != PadShape.Ring) continue;
                    double rel = (a - b.Start) % 360.0;
                    if (rel < 0) rel += 360.0;
                    if (rel < 90.0) return b;
                }
            }
            foreach (var b in buttons)
            {
                if (b.Shape == PadShape.Circle)
                {
                    var c = b.Center;
                    float ex = pt.X - c.X, ey = pt.Y - c.Y;
                    if (ex * ex + ey * ey <= (b.Box.Width / 2f) * (b.Box.Width / 2f)) return b;
                }
                else if (b.Shape == PadShape.Pill && b.Box.Contains(pt))
                {
                    return b;
                }
            }
            return null;
        }

        void Fire(PadButton b, bool longPress)
        {
            if (b.Click != null)
            {
                if (!longPress) b.Click();
                return;
            }
            if (b.Key < 0) return;
            if (!live)
            {
                Say("Not connected to the TV");
                return;
            }
            var h = longPress ? RemoteLongKey : RemoteKey;
            if (h != null) h(b.Key);
        }

        void Say(string text)
        {
            var h = Notice;
            if (h != null) h(text);
        }

        void UpdateTip()
        {
            string t = hover != null ? hover.Tip : null;
            if (t == tipText) return;
            tipText = t;
            tips.SetToolTip(this, t ?? "");
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            var h = HitTest(e.Location);
            if (pressed != null)
            {
                bool inside = h == pressed;
                if (inside != pressedInside) { pressedInside = inside; Invalidate(); }
            }
            if (h != hover)
            {
                hover = h;
                Cursor = h != null ? Cursors.Hand : Cursors.Default;
                UpdateTip();
                Invalidate();
            }
            base.OnMouseMove(e);
        }

        protected override void OnMouseLeave(EventArgs e)
        {
            if (hover != null)
            {
                hover = null;
                UpdateTip();
                Invalidate();
            }
            base.OnMouseLeave(e);
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            base.OnMouseDown(e);
            Focus();
            if (e.Button != MouseButtons.Left) return;
            var b = HitTest(e.Location);
            if (b == null) return;
            pressed = b;
            pressedInside = true;
            pressedAt = DateTime.UtcNow;
            holdFired = false;
            if (b.Repeat)
            {
                Fire(b, false);
                repeatTimer.Interval = 380;
                repeatTimer.Start();
            }
            if (b.Hold) holdTimer.Start();
            Invalidate();
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            base.OnMouseUp(e);
            var b = pressed;
            if (b == null) return;
            EndPress();
            if (!b.Repeat && !holdFired && HitTest(e.Location) == b) Fire(b, false);
        }

        protected override void OnMouseCaptureChanged(EventArgs e)
        {
            base.OnMouseCaptureChanged(e);
            if (pressed != null && !Capture && MouseButtons == MouseButtons.None) EndPress();
        }

        void EndPress()
        {
            repeatTimer.Stop();
            holdTimer.Stop();
            pressed = null;
            Invalidate();
        }

        void OnRepeat()
        {
            repeatTimer.Interval = 110;
            if (pressed != null && pressed.Repeat && pressedInside) Fire(pressed, false);
        }

        void OnHoldTick()
        {
            if (pressed == null)
            {
                holdTimer.Stop();
                return;
            }
            if (!holdFired && pressedInside && (DateTime.UtcNow - pressedAt).TotalMilliseconds >= HoldMs)
            {
                holdFired = true;
                Fire(pressed, true);
            }
            Invalidate();
        }

        protected override void OnMouseWheel(MouseEventArgs e)
        {
            base.OnMouseWheel(e);
            if (!live) return;
            wheel += e.Delta;
            while (wheel >= 120) { wheel -= 120; PressKey(19); }
            while (wheel <= -120) { wheel += 120; PressKey(20); }
        }

        void PressKey(int code)
        {
            FlashKey(code);
            var h = RemoteKey;
            if (h != null) h(code);
        }

        public void FlashKey(int code)
        {
            flash = buttons.FirstOrDefault(b => b.Key == code);
            flashTimer.Stop();
            flashTimer.Start();
            Invalidate();
        }

        protected override bool IsInputKey(Keys keyData)
        {
            switch (keyData & Keys.KeyCode)
            {
                case Keys.Up:
                case Keys.Down:
                case Keys.Left:
                case Keys.Right:
                case Keys.Enter:
                case Keys.Escape:
                case Keys.Back:
                case Keys.Space:
                case Keys.Home:
                    return true;
            }
            return base.IsInputKey(keyData);
        }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            if (e.Control && e.KeyCode == Keys.V)
            {
                e.Handled = true;
                e.SuppressKeyPress = true;
                Raise(PasteRequested);
                return;
            }
            if (e.Control || e.Alt)
            {
                base.OnKeyDown(e);
                return;
            }
            int code = -1;
            bool longPress = false;
            switch (e.KeyCode)
            {
                case Keys.Up: code = 19; break;
                case Keys.Down: code = 20; break;
                case Keys.Left: code = 21; break;
                case Keys.Right: code = 22; break;
                case Keys.Enter: code = 23; longPress = e.Shift; break;
                case Keys.Escape:
                case Keys.Back: code = 4; break;
                case Keys.Home:
                case Keys.H: code = 3; longPress = e.Shift; break;
                case Keys.M:
                case Keys.Apps: code = 82; break;
                case Keys.Space:
                case Keys.K: code = 85; break;
                case Keys.OemOpenBrackets:
                case Keys.Oemcomma: code = 89; break;
                case Keys.OemCloseBrackets:
                case Keys.OemPeriod: code = 90; break;
                case Keys.Oemplus:
                case Keys.Add:
                case Keys.PageUp: code = 24; break;
                case Keys.OemMinus:
                case Keys.Subtract:
                case Keys.PageDown: code = 25; break;
                case Keys.T:
                    e.Handled = true;
                    e.SuppressKeyPress = true;
                    Raise(TypeRequested);
                    return;
            }
            if (code < 0)
            {
                base.OnKeyDown(e);
                return;
            }
            e.Handled = true;
            e.SuppressKeyPress = true;
            FlashKey(code);
            if (!live)
            {
                Say("Not connected to the TV");
                return;
            }
            var h = longPress ? RemoteLongKey : RemoteKey;
            if (h != null) h(code);
        }

        // ---- painting

        bool IsDown(PadButton b) { return (b == pressed && pressedInside) || b == flash; }

        Color Fill(PadButton b)
        {
            if (IsDown(b))
            {
                if (b.Shape == PadShape.Ok) return Theme.Accent;
                if (b.Shape == PadShape.Ring) return Theme.RingPressed;
                return Theme.SurfacePressed;
            }
            if (b.On) return Theme.Accent;
            if (b == hover && live)
            {
                if (b.Shape == PadShape.Ok) return Theme.OkHover;
                if (b.Shape == PadShape.Ring) return Theme.RingHover;
                return Theme.SurfaceHover;
            }
            if (b.Shape == PadShape.Ok) return Theme.OkFill;
            if (b.Shape == PadShape.Ring) return Color.Empty;
            return Theme.Surface;
        }

        Color Ink(PadButton b)
        {
            if (b.On && !IsDown(b)) return Theme.AccentText;
            if (b.Click != null) return Theme.Icon;   // apps / screen / settings explain themselves if offline
            return live ? Theme.Icon : Theme.IconDim;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.Clear(BackColor);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
            g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;
            DrawRing(g);
            foreach (var b in buttons)
                if (b.Shape != PadShape.Ring) DrawButton(g, b);
        }

        void DrawRing(Graphics g)
        {
            using (var donut = new GraphicsPath(FillMode.Alternate))
            using (var br = new SolidBrush(Theme.Ring))
            {
                donut.AddEllipse(center.X - outerR, center.Y - outerR, outerR * 2, outerR * 2);
                donut.AddEllipse(center.X - innerR, center.Y - innerR, innerR * 2, innerR * 2);
                g.FillPath(br, donut);
            }
            foreach (var b in buttons)
            {
                if (b.Shape != PadShape.Ring) continue;
                Color fill = Fill(b);
                if (fill != Color.Empty)
                {
                    using (var seg = new GraphicsPath())
                    using (var br = new SolidBrush(fill))
                    {
                        seg.AddArc(center.X - outerR, center.Y - outerR, outerR * 2, outerR * 2, b.Start, 90);
                        seg.AddArc(center.X - innerR, center.Y - innerR, innerR * 2, innerR * 2, b.Start + 90, -90);
                        seg.CloseFigure();
                        g.FillPath(br, seg);
                    }
                }
            }
            using (var pen = new Pen(Theme.Bg, S(2.5f)))
            {
                for (int a = 45; a < 360; a += 90)
                {
                    double rad = a * Math.PI / 180.0;
                    float cx = (float)Math.Cos(rad), cy = (float)Math.Sin(rad);
                    g.DrawLine(pen, center.X + cx * (innerR - 2), center.Y + cy * (innerR - 2), center.X + cx * (outerR + 2), center.Y + cy * (outerR + 2));
                }
            }
            using (var pen = new Pen(Theme.RingEdge, S(1f)))
            {
                g.DrawEllipse(pen, center.X - outerR, center.Y - outerR, outerR * 2, outerR * 2);
                g.DrawEllipse(pen, center.X - innerR, center.Y - innerR, innerR * 2, innerR * 2);
            }
            float mid = (outerR + innerR) / 2f;
            foreach (var b in buttons)
            {
                if (b.Shape != PadShape.Ring) continue;
                double rad = (b.Start + 45) * Math.PI / 180.0;
                var p = new PointF(center.X + (float)Math.Cos(rad) * mid, center.Y + (float)Math.Sin(rad) * mid);
                Draw.Chevron(g, p, S(13), b.Start + 45, Ink(b), S(2.2f));
            }
        }

        void DrawButton(Graphics g, PadButton b)
        {
            Color fill = Fill(b);
            using (var br = new SolidBrush(fill))
            {
                if (b.Shape == PadShape.Pill)
                {
                    using (var path = Draw.RoundRect(b.Box, b.Box.Height / 2f)) g.FillPath(br, path);
                }
                else
                {
                    g.FillEllipse(br, b.Box);
                }
            }

            if (b.Shape == PadShape.Ok)
            {
                bool down = IsDown(b);
                using (var pen = new Pen(down ? Theme.Accent : Theme.OkEdge, S(1.5f)))
                    g.DrawEllipse(pen, RectangleF.Inflate(b.Box, -S(1), -S(1)));
                Color txt = down ? Theme.AccentText : (live ? Theme.Text : Theme.IconDim);
                TextRenderer.DrawText(g, "OK", okFont, Rectangle.Round(b.Box), txt,
                    TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine | TextFormatFlags.NoPrefix);
            }
            else if (b.Shape == PadShape.Pill)
            {
                float ic = S(17);
                Size ts = TextRenderer.MeasureText(g, b.Label, pillFont, Size.Empty, TextFormatFlags.NoPadding);
                float total = ic + S(6) + ts.Width;
                float x = b.Box.X + (b.Box.Width - total) / 2f;
                b.Art(g, new RectangleF(x, b.Center.Y - ic / 2f, ic, ic), Ink(b));
                var tr = new Rectangle((int)(x + ic + S(6)), (int)b.Box.Y, ts.Width + 4, (int)b.Box.Height);
                TextRenderer.DrawText(g, b.Label, pillFont, tr, Ink(b),
                    TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine | TextFormatFlags.NoPadding | TextFormatFlags.NoPrefix);
            }
            else if (b.Art != null)
            {
                float sz = b.Box.Width * 0.44f;
                var c = b.Center;
                b.Art(g, new RectangleF(c.X - sz / 2f, c.Y - sz / 2f, sz, sz), Ink(b));
            }

            if (b == pressed && b.Hold && pressedInside)
            {
                double t = Math.Min(1.0, (DateTime.UtcNow - pressedAt).TotalMilliseconds / HoldMs);
                if (t > 0.15)
                {
                    var rr = RectangleF.Inflate(b.Box, S(3), S(3));
                    using (var pen = Draw.RoundPen(Theme.Accent, S(2.5f)))
                        g.DrawArc(pen, rr, -90f, (float)(360.0 * t));
                }
            }
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                repeatTimer.Dispose();
                holdTimer.Dispose();
                flashTimer.Dispose();
                okFont.Dispose();
                pillFont.Dispose();
            }
            base.Dispose(disposing);
        }
    }

    // ------------------------------------------------------------------ favorites row

    sealed class FavoritesBar : Control
    {
        readonly float s;
        readonly ToolTip tips;
        readonly Font letterFont;
        readonly Font hintFont;
        List<AppEntry> items = new List<AppEntry>();
        int hover = -1;

        public event Action<string> Launch;
        public event Action<AppEntry, Point> MenuRequested;   // tile is null over an empty part of the row

        static readonly string[][] Brands = new[]
        {
            new[] { "discord", "5865F2" }, new[] { "youtube", "FF0000" }, new[] { "prime", "00A8E1" },
            new[] { "firebat", "00A8E1" }, new[] { "avod", "00A8E1" }, new[] { "netflix", "E50914" },
            new[] { "spotify", "1DB954" }, new[] { "disney", "113CCF" }, new[] { "twitch", "9146FF" },
            new[] { "plex", "E5A00D" }, new[] { "kodi", "17B2E7" }, new[] { "hulu", "1CE783" },
            new[] { "hbo", "5822B4" }, new[] { "crunchyroll", "F47521" }, new[] { "vlc", "FF8800" },
            new[] { "tubi", "FA382F" }, new[] { "peacock", "000000" }, new[] { "paramount", "0064FF" },
            new[] { "apple", "000000" }, new[] { "facebook", "1877F2" }, new[] { "twitter", "1D9BF0" },
            new[] { "crave", "00AEEF" }, new[] { "pluto", "FFD400" }
        };

        public FavoritesBar(float scale, ToolTip tips)
        {
            s = scale;
            this.tips = tips;
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint
                | ControlStyles.ResizeRedraw, true);
            BackColor = Theme.Bg;
            letterFont = Theme.SemiboldFont(13f);
            hintFont = Theme.UiFont(8.5f, FontStyle.Regular);
        }

        public void SetItems(List<AppEntry> list)
        {
            items = new List<AppEntry>(list);
            hover = -1;
            Invalidate();
        }

        public static Color BrandColor(AppEntry a)
        {
            string key = ((a.Name ?? "") + " " + (a.Package ?? "")).ToLowerInvariant();
            foreach (var b in Brands)
                if (key.Contains(b[0])) return Color.FromArgb(Convert.ToInt32(b[1], 16) | unchecked((int)0xFF000000));
            int h = 17;
            foreach (char ch in a.Package ?? "") h = h * 31 + ch;
            return ColorFromHue(Math.Abs(h % 360));
        }

        static Color ColorFromHue(int hue)
        {
            double hh = hue / 60.0, c = 0.55, x = c * (1 - Math.Abs(hh % 2 - 1)), m = 0.25;
            double r = 0, g = 0, b = 0;
            switch ((int)hh)
            {
                case 0: r = c; g = x; break;
                case 1: r = x; g = c; break;
                case 2: g = c; b = x; break;
                case 3: g = x; b = c; break;
                case 4: r = x; b = c; break;
                default: r = c; b = x; break;
            }
            return Color.FromArgb((int)((r + m) * 255), (int)((g + m) * 255), (int)((b + m) * 255));
        }

        static string Initial(AppEntry a)
        {
            foreach (char ch in a.Name ?? "")
                if (char.IsLetterOrDigit(ch)) return char.ToUpperInvariant(ch).ToString();
            return "?";
        }

        Rectangle TileRect(int i)
        {
            int slots = Math.Max(4, items.Count);
            int gap = (int)Math.Round(8 * s);
            int w = (Width - (slots - 1) * gap) / slots;
            return new Rectangle(i * (w + gap), 0, w, Height);
        }

        int HitTile(Point p)
        {
            for (int i = 0; i < items.Count; i++)
                if (TileRect(i).Contains(p)) return i;
            return -1;
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            base.OnMouseMove(e);
            int h = HitTile(e.Location);
            if (h == hover) return;
            hover = h;
            Cursor = h >= 0 ? Cursors.Hand : Cursors.Default;
            tips.SetToolTip(this, h >= 0 ? items[h].Name : "");
            Invalidate();
        }

        protected override void OnMouseLeave(EventArgs e)
        {
            base.OnMouseLeave(e);
            if (hover != -1) { hover = -1; Invalidate(); }
        }

        protected override void OnMouseClick(MouseEventArgs e)
        {
            base.OnMouseClick(e);
            int h = HitTile(e.Location);
            if (e.Button == MouseButtons.Left && h >= 0 && Launch != null) Launch(items[h].Package);
            if (e.Button == MouseButtons.Right && MenuRequested != null) MenuRequested(h >= 0 ? items[h] : null, e.Location);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.Clear(Theme.Bg);
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
            if (items.Count == 0)
            {
                using (var br = new SolidBrush(Theme.IconDim))
                using (var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center })
                    g.DrawString("Pin apps from Apps, or right-click to add a site", hintFont, br, new RectangleF(0, 0, Width, Height), sf);
                return;
            }
            for (int i = 0; i < items.Count; i++)
            {
                var r = TileRect(i);
                Color c = BrandColor(items[i]);
                if (i == hover) c = ControlPaint.Light(c, 0.25f);
                bool lightBg = (c.R * 299 + c.G * 587 + c.B * 114) / 1000 > 160;
                using (var path = Draw.RoundRect(new RectangleF(r.X, r.Y, r.Width, r.Height), 9 * s))
                using (var fill = new SolidBrush(c))
                    g.FillPath(fill, path);
                using (var br = new SolidBrush(lightBg ? Color.FromArgb(24, 24, 24) : Color.White))
                using (var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center })
                    g.DrawString(Initial(items[i]), letterFont, br, new RectangleF(r.X, r.Y, r.Width, r.Height), sf);
            }
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                letterFont.Dispose();
                hintFont.Dispose();
            }
            base.Dispose(disposing);
        }
    }

    // ------------------------------------------------------------------ main window

    sealed class SiteDialog : Form
    {
        readonly float s;
        readonly FieldBox address, title;
        readonly Label note;

        public string Url { get; private set; }
        public string SiteName { get; private set; }

        public SiteDialog(float scale)
        {
            s = scale;
            Text = "Add website tile";
            AutoScaleMode = AutoScaleMode.None;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            ShowInTaskbar = false;
            StartPosition = FormStartPosition.CenterParent;
            KeyPreview = true;
            BackColor = Theme.Bg;
            ForeColor = Theme.Text;
            Font = Theme.UiFont(9f, FontStyle.Regular);
            ClientSize = new Size(S(300), S(176));

            address = new FieldBox(s, null);
            address.Bounds = new Rectangle(S(14), S(14), S(272), S(36));
            address.Cue = "Address, e.g. youtube.com";
            title = new FieldBox(s, null);
            title.Bounds = new Rectangle(S(14), S(58), S(272), S(36));
            title.Cue = "Tile name (optional)";
            note = new Label { Bounds = new Rectangle(S(14), S(100), S(272), S(22)), ForeColor = Theme.SubText,
                BackColor = Theme.Bg, Text = "Opens in Sofa TV Browser", TextAlign = ContentAlignment.MiddleLeft };

            var cancel = new FlatButton { Text = "Cancel", Bounds = new Rectangle(S(14), S(128), S(132), S(34)) };
            cancel.Click += delegate { Close(); };
            var add = new FlatButton { Text = "Add", Accent = true, Bounds = new Rectangle(S(154), S(128), S(132), S(34)) };
            add.Click += delegate { Accept(); };

            Controls.Add(address);
            Controls.Add(title);
            Controls.Add(note);
            Controls.Add(cancel);
            Controls.Add(add);
        }

        int S(float v) { return (int)Math.Round(v * s); }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            address.Box.Focus();
        }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            base.OnKeyDown(e);
            if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; Accept(); }
            else if (e.KeyCode == Keys.Escape) { e.SuppressKeyPress = true; Close(); }
        }

        void Accept()
        {
            string url = Session.CleanSite(address.Box.Text);
            if (url == null)
            {
                note.ForeColor = Theme.Amber;
                note.Text = "Enter a web address, e.g. youtube.com";
                address.Box.Focus();
                return;
            }
            string name = title.Box.Text.Trim();
            if (name.Length == 0)
            {
                name = new Uri(url).Host;
                if (name.StartsWith("www.", StringComparison.OrdinalIgnoreCase)) name = name.Substring(4);
            }
            Url = url;
            SiteName = name;
            DialogResult = DialogResult.OK;
        }
    }

    sealed class RemoteForm : Form
    {
        [DllImport("dwmapi.dll")]
        static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
        [DllImport("user32.dll")]
        static extern bool RegisterHotKey(IntPtr hWnd, int id, uint modifiers, uint virtualKey);
        [DllImport("user32.dll")]
        static extern bool UnregisterHotKey(IntPtr hWnd, int id);

        const int WmHotkey = 0x0312;
        const int MouseHotkeyId = 1;
        const uint CtrlAltNoRepeat = 0x4003;   // MOD_ALT | MOD_CONTROL | MOD_NOREPEAT

        readonly Settings settings;
        readonly Session session;
        readonly float s;
        readonly ToolTip tips = new ToolTip();
        readonly System.Windows.Forms.Timer noticeTimer = new System.Windows.Forms.Timer();
        HeaderBar header;
        FieldBox ipField, typeField;
        FlatButton findButton, connectButton;
        RemotePad pad;
        FavoritesBar favBar;
        ContextMenuStrip moreMenu, appsMenu, tileMenu;
        bool hotkeyBusy;
        List<AppEntry> appsCache;
        bool appsLoading;
        Link state = Link.Idle;
        string stateText = "Starting\u2026";
        char pendingHigh;

        public RemoteForm()
        {
            settings = Settings.Load();
            float dpi = 96f;
            try { using (var g = Graphics.FromHwnd(IntPtr.Zero)) dpi = g.DpiX; } catch { }
            s = Math.Max(1f, dpi / 96f);

            Text = "Fire Remote";
            AutoScaleMode = AutoScaleMode.None;
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            BackColor = Theme.Bg;
            ForeColor = Theme.Text;
            Font = Theme.UiFont(9f, FontStyle.Regular);
            ClientSize = new Size(S(300), S(630));
            try { Icon = System.Drawing.Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

            tips.InitialDelay = 500;
            tips.ReshowDelay = 150;
            tips.AutoPopDelay = 8000;
            noticeTimer.Tick += delegate { noticeTimer.Stop(); RenderStatus(); };

            BuildUi();
            TopMost = settings.TopMost;
            header.Pinned = settings.TopMost;
            PlaceWindow();

            session = new Session(settings);
            session.Changed += delegate (Link st, string msg) { Ui(delegate { OnSessionChanged(st, msg); }); };
            session.MouseStopped += delegate { Ui(delegate { pad.MouseOn = false; ShowNotice("TV mouse stopped"); }); };
        }

        int S(float v) { return (int)Math.Round(v * s); }

        void Ui(Action a)
        {
            if (IsDisposed) return;
            try
            {
                if (InvokeRequired) BeginInvoke(a);
                else a();
            }
            catch (InvalidOperationException) { }   // also covers ObjectDisposedException
        }

        void BuildUi()
        {
            int w = ClientSize.Width;

            header = new HeaderBar(s);
            header.Bounds = new Rectangle(0, 0, w, S(50));
            header.PinClicked += delegate
            {
                TopMost = !TopMost;
                header.Pinned = TopMost;
                settings.TopMost = TopMost;
                settings.Save();
            };
            header.MoreClicked += delegate { moreMenu.Show(header.MoreButton, new Point(0, header.MoreButton.Height)); };
            header.StatusClicked += delegate { if (state == Link.NoAdb) LocateAdb(); };
            tips.SetToolTip(header.PinButton, "Keep on top");
            tips.SetToolTip(header.MoreButton, "More");

            ipField = new FieldBox(s, null);
            ipField.Bounds = new Rectangle(S(14), S(54), S(148), S(34));
            ipField.Cue = "TV IP address";
            ipField.Box.Text = settings.Ip;
            ipField.Box.Enter += delegate { BeginInvoke(new Action(ipField.Box.SelectAll)); };
            ipField.Box.KeyDown += delegate (object o, KeyEventArgs e)
            {
                if (e.KeyCode == Keys.Enter)
                {
                    e.SuppressKeyPress = true;
                    ConnectClicked();
                }
            };

            findButton = new FlatButton();
            findButton.Art = Glyphs.Search;
            findButton.Radius = S(17);
            findButton.Bounds = new Rectangle(S(168), S(54), S(34), S(34));
            findButton.Click += delegate { session.Find(); };
            tips.SetToolTip(findButton, "Find the Fire TV on your hotspot");

            connectButton = new FlatButton();
            connectButton.Text = "Connect";
            connectButton.Accent = true;
            connectButton.Bounds = new Rectangle(S(208), S(54), S(78), S(34));
            connectButton.Click += delegate { ConnectClicked(); };

            pad = new RemotePad(s, tips);
            pad.Bounds = new Rectangle(0, S(98), w, S(446));
            pad.RemoteKey += delegate (int code) { session.Key(code); };
            pad.RemoteLongKey += delegate (int code) { session.LongPress(code); };
            pad.Notice += ShowNotice;
            pad.TypeRequested += delegate { typeField.Box.Focus(); };
            pad.PasteRequested += PasteToTv;
            pad.SettingsClicked += delegate { if (Require()) session.OpenSettings(); };
            pad.AppsClicked += ShowApps;
            pad.ScreenClicked += StartMirror;
            pad.MouseModeClicked += ToggleMouse;

            favBar = new FavoritesBar(s, tips);
            favBar.Bounds = new Rectangle(S(14), S(548), S(272), S(32));
            favBar.SetItems(settings.Favorites);
            favBar.Launch += OpenTile;
            favBar.MenuRequested += ShowTileMenu;

            typeField = new FieldBox(s, Glyphs.Keyboard);
            typeField.Bounds = new Rectangle(S(14), S(588), S(272), S(36));
            typeField.Cue = "Type on the TV\u2026";
            typeField.Box.ContextMenuStrip = new ContextMenuStrip();   // no local-only paste menu
            typeField.Box.PreviewKeyDown += delegate (object o, PreviewKeyDownEventArgs e)
            {
                switch (e.KeyCode)
                {
                    case Keys.Tab:
                    case Keys.Up:
                    case Keys.Down:
                    case Keys.Left:
                    case Keys.Right:
                    case Keys.Enter:
                    case Keys.Escape:
                        e.IsInputKey = true;
                        break;
                }
            };
            typeField.Box.KeyDown += TypeKeyDown;
            typeField.Box.KeyPress += TypeKeyPress;

            Controls.Add(header);
            Controls.Add(ipField);
            Controls.Add(findButton);
            Controls.Add(connectButton);
            Controls.Add(pad);
            Controls.Add(favBar);
            Controls.Add(typeField);

            moreMenu = MakeMenu();
            AddItem(moreMenu.Items, "Find the TV on my hotspot", delegate { session.Find(); });
            AddItem(moreMenu.Items, "Reconnect", delegate { session.Connect(session.Ip ?? ipField.Box.Text); });
            AddItem(moreMenu.Items, "Show the TV screen (scrcpy)", delegate { StartMirror(); });
            AddItem(moreMenu.Items, "Add website tile\u2026", delegate { AddSite(); });
            AddItem(moreMenu.Items, "Remote buttons\u2026", delegate { ShowButtons(); });
            moreMenu.Items.Add(new ToolStripSeparator());
            AddItem(moreMenu.Items, "Locate adb.exe\u2026", delegate { LocateAdb(); });
            AddItem(moreMenu.Items, "Keyboard shortcuts", delegate { ShowShortcuts(); });
            AddItem(moreMenu.Items, "About Fire Remote", delegate { ShowAbout(); });

            appsMenu = MakeMenu();
            tileMenu = MakeMenu();
        }

        static ContextMenuStrip MakeMenu()
        {
            var m = new ContextMenuStrip();
            m.Renderer = new DarkMenuRenderer();
            m.ShowImageMargin = false;
            m.BackColor = Theme.Menu;
            m.ForeColor = Theme.Text;
            m.Font = Theme.UiFont(9.5f, FontStyle.Regular);
            return m;
        }

        static ToolStripMenuItem AddItem(ToolStripItemCollection items, string text, EventHandler click)
        {
            var it = new ToolStripMenuItem((text ?? "").Replace("&", "&&"));
            it.ForeColor = Theme.Text;
            if (click != null) it.Click += click;
            else it.Enabled = false;
            items.Add(it);
            return it;
        }

        // ---- window behaviour

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            if (!Tools.IsWindows) return;
            try
            {
                int on = 1;
                if (DwmSetWindowAttribute(Handle, 20, ref on, 4) != 0) DwmSetWindowAttribute(Handle, 19, ref on, 4);   // dark title bar
            }
            catch { }
            try { hotkeyBusy = !RegisterHotKey(Handle, MouseHotkeyId, CtrlAltNoRepeat, (uint)Keys.M); }
            catch { hotkeyBusy = true; }
        }

        protected override void OnHandleDestroyed(EventArgs e)
        {
            if (Tools.IsWindows)
            {
                try { UnregisterHotKey(Handle, MouseHotkeyId); } catch { }
            }
            base.OnHandleDestroyed(e);
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WmHotkey && m.WParam.ToInt64() == MouseHotkeyId) BeginInvoke(new Action(ToggleMouse));
            base.WndProc(ref m);
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            pad.Focus();
            session.Start();
            if (hotkeyBusy) ShowNotice("Ctrl+Alt+M is used by another app");
        }

        protected override void OnActivated(EventArgs e)
        {
            base.OnActivated(e);
            if (ActiveControl == null) pad.Focus();
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            settings.TopMost = TopMost;
            if (WindowState == FormWindowState.Normal)
            {
                settings.X = Left;
                settings.Y = Top;
            }
            settings.Save();
            session.Shutdown();
            base.OnFormClosing(e);
        }

        public void ShowFromOtherInstance()
        {
            if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
            Activate();
            bool top = TopMost;
            TopMost = true;
            TopMost = top;
        }

        void PlaceWindow()
        {
            StartPosition = FormStartPosition.CenterScreen;
            if (settings.X == int.MinValue) return;
            var r = new Rectangle(settings.X, settings.Y, Width, Height);
            foreach (var sc in Screen.AllScreens)
            {
                var vis = Rectangle.Intersect(sc.WorkingArea, r);
                if (vis.Width > S(80) && vis.Height > S(80))
                {
                    StartPosition = FormStartPosition.Manual;
                    Location = r.Location;
                    return;
                }
            }
        }

        // ---- session state

        void OnSessionChanged(Link st, string msg)
        {
            state = st;
            stateText = msg;
            bool online = st == Link.Online, busy = st == Link.Busy;
            pad.Live = online;
            connectButton.Text = st == Link.NoAdb ? "Locate" : online ? "Disconnect" : busy ? "Cancel" : "Connect";
            connectButton.Accent = !online && !busy;
            connectButton.Invalidate();
            findButton.Enabled = !busy && !online && st != Link.NoAdb;
            ipField.Box.ReadOnly = busy || online;
            if (online && session.Ip != null) ipField.Box.Text = session.Ip;
            if (!online)
            {
                appsCache = null;
                appsLoading = false;
            }
            RenderStatus();
        }

        void RenderStatus()
        {
            if (noticeTimer.Enabled) return;
            Color dot;
            switch (state)
            {
                case Link.Online: dot = session.Instant ? Theme.Green : Theme.Amber; break;
                case Link.Busy: dot = Theme.Blue; break;
                case Link.NoAdb: dot = Theme.Red; break;
                default: dot = Theme.Gray; break;
            }
            header.SetStatus(stateText, dot, state == Link.NoAdb);
            string why = session.Why;
            tips.SetToolTip(header, state == Link.Online && !session.Instant && why != null ? "Instant mode unavailable: " + why : "");
        }

        void ShowNotice(string text)
        {
            header.SetStatus(text, Theme.Amber, false);
            noticeTimer.Stop();
            noticeTimer.Interval = 1800;
            noticeTimer.Start();
        }

        bool Require()
        {
            if (session.Online) return true;
            ShowNotice("Not connected to the TV");
            return false;
        }

        void ConnectClicked()
        {
            if (state == Link.NoAdb) { LocateAdb(); return; }
            if (state == Link.Online) { session.Disconnect(); return; }
            if (state == Link.Busy) { session.Cancel(); return; }
            string ip = ipField.Box.Text.Trim();
            if (ip.Length == 0) session.Find();
            else session.Connect(ip);
            pad.Focus();
        }

        void LocateAdb()
        {
            using (var dlg = new OpenFileDialog())
            {
                dlg.Title = "Locate adb.exe (it is in your scrcpy folder)";
                dlg.Filter = Tools.IsWindows ? "adb.exe|adb.exe|Programs (*.exe)|*.exe" : "adb|adb*";
                if (dlg.ShowDialog(this) == DialogResult.OK) session.SetAdb(dlg.FileName);
            }
        }

        // ---- typing

        void TypeKeyDown(object sender, KeyEventArgs e)
        {
            if ((e.Control && e.KeyCode == Keys.V) || (e.Shift && e.KeyCode == Keys.Insert))
            {
                e.Handled = true;
                e.SuppressKeyPress = true;
                PasteToTv();
                return;
            }
            if (e.Control || e.Alt) return;
            int code = -1;
            switch (e.KeyCode)
            {
                case Keys.Enter: code = 66; break;     // KEYCODE_ENTER
                case Keys.Back: code = 67; break;      // KEYCODE_DEL
                case Keys.Delete: code = 112; break;   // KEYCODE_FORWARD_DEL
                case Keys.Tab: code = 61; break;       // next field on the TV
                case Keys.Up: code = 19; break;
                case Keys.Down: code = 20; break;
                case Keys.Left: code = 21; break;
                case Keys.Right: code = 22; break;
                case Keys.Escape:
                    e.SuppressKeyPress = true;
                    pad.Focus();
                    return;
            }
            if (code < 0) return;
            if (code != 67)
            {
                e.Handled = true;
                e.SuppressKeyPress = true;
            }
            if (!session.Online)
            {
                ShowNotice("Not connected to the TV");
                e.SuppressKeyPress = true;
                return;
            }
            session.Key(code);
            if (code == 66) typeField.Box.Clear();
        }

        void TypeKeyPress(object sender, KeyPressEventArgs e)
        {
            char ch = e.KeyChar;
            if (char.IsControl(ch))
            {
                if (ch != '\b') e.Handled = true;
                return;
            }
            if (!session.Online)
            {
                e.Handled = true;
                ShowNotice("Not connected to the TV");
                return;
            }
            if (char.IsHighSurrogate(ch))
            {
                pendingHigh = ch;
                return;
            }
            string text = ch.ToString();
            if (char.IsLowSurrogate(ch))
            {
                if (pendingHigh == '\0') return;
                text = new string(new[] { pendingHigh, ch });
                pendingHigh = '\0';
            }
            session.Text(text);
        }

        void PasteToTv()
        {
            if (!Require()) return;
            string text = null;
            try { if (Clipboard.ContainsText()) text = Clipboard.GetText(); } catch { }
            if (string.IsNullOrEmpty(text))
            {
                ShowNotice("The clipboard has no text");
                return;
            }
            session.Text(text);
            if (typeField.Box.Focused) typeField.Box.AppendText(text.Replace("\r", "").Replace("\n", " "));
            ShowNotice("Sent " + text.Length + " characters to the TV");
        }

        // ---- apps menu, screen, dialogs

        void ShowApps()
        {
            if (!Require()) return;
            if (appsCache == null && !appsLoading) LoadApps();
            PopulateApps();
            var r = pad.AppsRect;
            appsMenu.Show(pad, new Point(r.Left, r.Top), ToolStripDropDownDirection.AboveRight);
        }

        void LoadApps()
        {
            appsLoading = true;
            session.LoadApps(delegate (List<AppEntry> list)
            {
                Ui(delegate
                {
                    appsLoading = false;
                    appsCache = list ?? new List<AppEntry>();
                    PopulateApps();
                });
            });
        }

        void PopulateApps()
        {
            appsMenu.SuspendLayout();
            appsMenu.Items.Clear();
            if (appsCache == null)
            {
                AddItem(appsMenu.Items, "Loading apps\u2026", null);
            }
            else
            {
                var byName = StringComparer.CurrentCultureIgnoreCase;
                var user = appsCache.Where(a => !a.IsSystem).OrderBy(a => a.Name, byName).ToList();
                var sys = appsCache.Where(a => a.IsSystem).OrderBy(a => a.Name, byName).ToList();
                if (user.Count == 0 && sys.Count == 0) AddItem(appsMenu.Items, "No apps found", null);
                foreach (var a in user) AddApp(appsMenu.Items, a);
                if (sys.Count > 0)
                {
                    if (user.Count > 0) appsMenu.Items.Add(new ToolStripSeparator());
                    var sub = new ToolStripMenuItem("Amazon && system apps");
                    sub.ForeColor = Theme.Text;
                    sub.DropDown.Renderer = new DarkMenuRenderer();
                    sub.DropDown.BackColor = Theme.Menu;
                    sub.DropDown.ForeColor = Theme.Text;
                    ((ToolStripDropDownMenu)sub.DropDown).ShowImageMargin = false;
                    foreach (var a in sys) AddApp(sub.DropDownItems, a);
                    appsMenu.Items.Add(sub);
                }
                appsMenu.Items.Add(new ToolStripSeparator());
                AddItem(appsMenu.Items, "Right-click an app to pin or unpin it", null);
                AddItem(appsMenu.Items, "Refresh list", delegate
                {
                    appsCache = null;
                    BeginInvoke(new Action(ShowApps));
                });
            }
            appsMenu.ResumeLayout();
        }

        void AddApp(ToolStripItemCollection items, AppEntry a)
        {
            string package = a.Package;
            string name = a.Name;
            var it = AddItem(items, FavIndex(package) >= 0 ? "\u2605 " + name : name, delegate { session.LaunchApp(package); });
            it.ToolTipText = package;
            it.MouseUp += delegate (object o, MouseEventArgs e)
            {
                if (e.Button != MouseButtons.Right) return;
                ToggleFavorite(a);
                it.Text = (FavIndex(package) >= 0 ? "\u2605 " + name : name).Replace("&", "&&");
            };
        }

        int FavIndex(string package)
        {
            return settings.Favorites.FindIndex(f => string.Equals(f.Package, package, StringComparison.Ordinal));
        }

        void ToggleFavorite(AppEntry a)
        {
            int i = FavIndex(a.Package);
            if (i >= 0)
            {
                settings.Favorites.RemoveAt(i);
            }
            else if (settings.Favorites.Count >= Settings.MaxFavorites)
            {
                ShowNotice("Favorites are full (" + Settings.MaxFavorites + ") \u2014 unpin one first");
                return;
            }
            else
            {
                settings.Favorites.Add(new AppEntry { Name = a.Name, Package = a.Package });
            }
            settings.Save();
            favBar.SetItems(settings.Favorites);
        }

        void OpenTile(string target)
        {
            if (!Require()) return;
            if (!Session.IsSite(target))
            {
                session.LaunchApp(target);
                return;
            }
            session.OpenSite(target, delegate (string why) { Ui(delegate { ShowNotice(why); }); });
        }

        void ShowTileMenu(AppEntry tile, Point at)
        {
            tileMenu.Items.Clear();
            AddItem(tileMenu.Items, "Add website tile\u2026", delegate { AddSite(); });
            if (tile != null) AddItem(tileMenu.Items, "Remove " + tile.Name, delegate { ToggleFavorite(tile); });
            tileMenu.Show(favBar, at);
        }

        void AddSite()
        {
            if (settings.Favorites.Count >= Settings.MaxFavorites)
            {
                ShowNotice("Favorites are full (" + Settings.MaxFavorites + ") \u2014 remove one first");
                return;
            }
            using (var dlg = new SiteDialog(s))
            {
                if (dlg.ShowDialog(this) != DialogResult.OK) return;
                if (FavIndex(dlg.Url) >= 0) ShowNotice("That site already has a tile");
                else ToggleFavorite(new AppEntry { Name = dlg.SiteName, Package = dlg.Url });
            }
        }

        void ShowButtons()
        {
            if (!Require()) return;
            using (var dlg = new ButtonsDialog(s, session, appsCache)) dlg.ShowDialog(this);
        }

        void StartMirror()
        {
            if (!Require()) return;
            string err;
            if (!session.StartMirror(out err))
                MessageBox.Show(this, err, "Fire Remote", MessageBoxButtons.OK, MessageBoxIcon.Information);
            else
                ShowNotice("Opening the TV screen\u2026");
        }

        void ToggleMouse()
        {
            if (pad.MouseOn)
            {
                session.StopMouse();
                pad.MouseOn = false;
                return;
            }
            if (!Require()) return;
            string err;
            if (!session.StartMouse(out err))
            {
                MessageBox.Show(this, err, "Fire Remote", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            pad.MouseOn = true;
            ShowNotice("Click the scrcpy window");
        }

        void ShowShortcuts()
        {
            MessageBox.Show(this,
                "Arrow keys\tD-pad\n" +
                "Enter\t\tOK   (Shift+Enter: long-press)\n" +
                "Esc / Backspace\tBack\n" +
                "H / Home\tHome   (Shift+H: quick menu)\n" +
                "M\t\tMenu / options\n" +
                "Space\t\tPlay / pause\n" +
                "[  ]\t\tRewind / fast-forward\n" +
                "+  \u2212\t\tVolume (if the TV supports HDMI-CEC)\n" +
                "Mouse wheel\tUp / down\n" +
                "T\t\tType on the TV\n" +
                "Ctrl+V\t\tPaste text to the TV\n" +
                "Ctrl+Alt+M\tTV mouse on / off (from any window)\n\n" +
                "Hold a button: arrows, volume and seek repeat;\nOK, Home, Back and Menu long-press.",
                "Keyboard shortcuts", MessageBoxButtons.OK, MessageBoxIcon.None);
        }

        void ShowAbout()
        {
            var server = session.Server;
            string mode = !session.Online ? "not connected" : session.Instant
                ? "instant (scrcpy-server " + (server != null ? server.Version : "?") + ")"
                : "basic (adb input)" + (session.Why != null ? "\nInstant mode unavailable: " + session.Why : "");
            var adb = session.Adb;
            MessageBox.Show(this,
                "Fire Remote " + Program.AppVersion + "\n\n" +
                "Mode: " + mode + "\n" +
                "adb: " + (adb != null ? adb.ExePath : "not found") + "\n" +
                "Settings: " + Program.DataDir + "\n\n" +
                "Instant mode drives scrcpy-server by Genymobile (Apache License 2.0), taken from your scrcpy folder.",
                "About Fire Remote", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
    }
}
