// KDTube Setup for Windows.
//
// KDTube is an Android app, so this program doesn't install anything on the PC: it carries the app
// (for every processor type) and the Android tool "adb", and puts the app on an Android TV, a Fire TV,
// a phone/tablet or an emulator. One file, nothing else to download.
//
// Built by packaging/make-installers.sh with the C# compiler that is a part of Windows (.NET Framework 4):
// keep the code to what that compiler knows (C# 5).
//
//   KDTubeSetup.exe                                  the window
//   KDTubeSetup.exe /silent /device:192.168.1.50     no window (exit code 0 = installed), for scripts
//       /device:<address or serial>  /wsa  /nolaunch  /replace  /log:<file>

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

[assembly: AssemblyTitle("KDTube for TV: installer")]
[assembly: AssemblyProduct("KDTube")]
[assembly: AssemblyDescription("Installs KDTube on an Android TV, Fire TV, phone or emulator")]
[assembly: AssemblyVersion(BuildInfo.AssemblyVersion)]
[assembly: AssemblyFileVersion(BuildInfo.AssemblyVersion)]

namespace KDTubeSetup
{
    internal class SetupException : Exception
    {
        public SetupException(string message) : base(message) { }
    }

    /// <summary>The work itself. No UI in here: questions and progress go through the delegates.</summary>
    internal class Installer
    {
        private const string PackageName = "com.kdtube.app";
        private const string LaunchActivity = PackageName + "/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity";
        private const string WsaAddress = "127.0.0.1:58526";
        private static readonly string[] AdbFiles = { "adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll", "libwinpthread-1.dll", "NOTICE.txt" };
        private static readonly Regex Address = new Regex(@"^\d{1,3}(\.\d{1,3}){3}(:\d+)?$");

        public Action<string> Log = delegate { };
        /// <summary>Asked before another build of the app is removed from the device. Returns true to go on.</summary>
        public Func<string, bool> Confirm = delegate { return false; };
        public bool Launch = true;

        private readonly string mWorkDir;
        private string mAdb;

        public Installer()
        {
            mWorkDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "KDTube", "setup-" + BuildInfo.Version);
        }

        /// <param name="device">An address in the network, a serial number, "wsa", or empty: the only connected device</param>
        public void Run(string device)
        {
            PrepareAdb();

            string target = ResolveTarget(device);
            WaitAuthorized(target);

            string abi;
            string apk = SelectApk(target, out abi);
            Log("Processor type of the device: " + abi);

            Install(target, apk);
            Optimize(target);

            if (Launch)
            {
                Adb("-s", target, "shell", "am", "start", "-n", LaunchActivity);
                Log("KDTube is installed and started on the device.");
            }
            else
            {
                Log("KDTube is installed.");
            }
        }

        public List<string> ConnectedDevices()
        {
            PrepareAdb();
            List<string> result = new List<string>();

            foreach (string line in Adb("devices").Split('\n'))
            {
                Match match = Regex.Match(line.Trim(), @"^(\S+)\s+(device|unauthorized|offline)$");
                if (match.Success)
                {
                    result.Add(match.Groups[1].Value);
                }
            }

            return result;
        }

        private void PrepareAdb()
        {
            if (mAdb != null)
            {
                return;
            }

            string dir = Path.Combine(mWorkDir, "platform-tools");
            Directory.CreateDirectory(dir);

            foreach (string name in AdbFiles)
            {
                Extract("adb/" + name, Path.Combine(dir, name));
            }

            mAdb = Path.Combine(dir, "adb.exe");
        }

        private string ResolveTarget(string device)
        {
            device = (device ?? "").Trim();

            if (device.Equals("wsa", StringComparison.OrdinalIgnoreCase))
            {
                device = WsaAddress;
            }

            if (device.Length == 0)
            {
                List<string> connected = ConnectedDevices();

                if (connected.Count == 1)
                {
                    Log("Using the connected device: " + connected[0]);
                    return connected[0];
                }

                throw new SetupException(connected.Count == 0
                        ? "No device is connected. Type the address of the device, or connect it with a USB cable (with 'USB debugging' switched on)."
                        : "Several devices are connected (" + string.Join(", ", connected.ToArray()) + "). Type the address or the name of the one to use.");
            }

            // An address: connect over the network. Anything else is the name of a device that is attached already.
            if (Address.IsMatch(device))
            {
                if (device.IndexOf(':') < 0)
                {
                    device += ":5555";
                }

                Log("Connecting to " + device + " ...");
                string answer = Adb("connect", device);
                Log("  " + answer);

                if (Regex.IsMatch(answer, "refused|failed|unable|cannot", RegexOptions.IgnoreCase))
                {
                    throw new SetupException("Can't connect to " + device + ". Check the address, and that 'ADB debugging' (or 'USB debugging' / 'Network debugging') " +
                            "is switched on in the developer options of the device. The PC and the device have to be in the same network.");
                }
            }

            return device;
        }

        private void WaitAuthorized(string target)
        {
            bool told = false;

            for (int i = 0; i < 60; i++)
            {
                if (Adb("-s", target, "get-state").Trim() == "device")
                {
                    return;
                }

                if (!told)
                {
                    Log("Look at the screen of the device: it asks to allow debugging from this computer. Choose 'Always allow' and OK.");
                    told = true;
                }

                Thread.Sleep(2000);
            }

            throw new SetupException("The device didn't allow the connection (waited 2 minutes).");
        }

        private string SelectApk(string target, out string selectedAbi)
        {
            string abis = Adb("-s", target, "shell", "getprop", "ro.product.cpu.abilist").Trim();

            // NOTE: the list is in the order of the preference of the device
            foreach (string item in abis.Split(','))
            {
                string abi = item.Trim();

                if (HasResource("apk/" + abi))
                {
                    string path = Path.Combine(mWorkDir, "KDTube-" + BuildInfo.Version + "-" + abi + ".apk");
                    Extract("apk/" + abi, path);
                    selectedAbi = abi;
                    return path;
                }
            }

            throw new SetupException("There's no build for this device (it reports: " + abis + "). Builds inside: arm64-v8a, armeabi-v7a, x86.");
        }

        private void Install(string target, string apk)
        {
            Log("Installing (takes a minute over Wi-Fi) ...");
            string answer = Adb("-s", target, "install", "-r", apk);

            if (Regex.IsMatch(answer, "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match"))
            {
                if (!Confirm("A build of KDTube that was signed with another key is installed on the device already.\n\n" +
                        "It has to be removed first. Its settings and sign-in on the device will be lost.\n\nRemove it and install KDTube?"))
                {
                    throw new SetupException("Another build of the app is installed on the device. Nothing was changed.");
                }

                Adb("-s", target, "uninstall", PackageName);
                answer = Adb("-s", target, "install", "-r", apk);
            }

            if (answer.IndexOf("Success", StringComparison.Ordinal) < 0)
            {
                if (answer.Contains("INSTALL_FAILED_INSUFFICIENT_STORAGE"))
                {
                    throw new SetupException("Not enough free space on the device (about 150 MB is needed).");
                }

                throw new SetupException("The device refused the app: " + answer.Trim());
            }
        }

        /// <summary>
        /// Asks the device to compile the app for its processor right now. Otherwise the first days it runs
        /// slower (Android does this later by itself, when the device is idle). Not fatal if it isn't possible.
        /// </summary>
        private void Optimize(string target)
        {
            Log("Optimizing the app for this device (a minute or two) ...");
            string answer = Adb("-s", target, "shell", "cmd", "package", "compile", "-m", "speed", "-f", PackageName);

            if (answer.IndexOf("Success", StringComparison.Ordinal) < 0)
            {
                Log("  skipped (the device will do it later by itself)");
            }
        }

        private string Adb(params string[] arguments)
        {
            StringBuilder line = new StringBuilder();

            foreach (string argument in arguments)
            {
                if (line.Length > 0)
                {
                    line.Append(' ');
                }

                line.Append(argument.IndexOfAny(new[] { ' ', '"' }) >= 0 ? "\"" + argument.Replace("\"", "\\\"") + "\"" : argument);
            }

            ProcessStartInfo info = new ProcessStartInfo(mAdb, line.ToString());
            info.RedirectStandardOutput = true;
            info.RedirectStandardError = true;
            info.UseShellExecute = false;
            info.CreateNoWindow = true;

            using (Process process = Process.Start(info))
            {
                StringBuilder error = new StringBuilder();
                process.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs e) { if (e.Data != null) { error.AppendLine(e.Data); } };
                process.BeginErrorReadLine();
                string output = process.StandardOutput.ReadToEnd();
                process.WaitForExit();
                return (output + error).Trim();
            }
        }

        private static bool HasResource(string name)
        {
            return Assembly.GetExecutingAssembly().GetManifestResourceInfo(name) != null;
        }

        /// <summary>Copies a file that is carried inside of this program to the disk (once)</summary>
        private static void Extract(string resource, string path)
        {
            using (Stream input = Assembly.GetExecutingAssembly().GetManifestResourceStream(resource))
            {
                if (input == null)
                {
                    throw new SetupException("This setup file is damaged (a part is missing: " + resource + ").");
                }

                if (File.Exists(path) && new FileInfo(path).Length == input.Length)
                {
                    return;
                }

                try
                {
                    using (FileStream output = new FileStream(path, FileMode.Create, FileAccess.Write))
                    {
                        input.CopyTo(output);
                    }
                }
                catch (IOException)
                {
                    // adb is running from an earlier start of the setup and its files are in use: they are the same
                    if (!File.Exists(path))
                    {
                        throw;
                    }
                }
            }
        }
    }

    internal class MainForm : Form
    {
        private readonly RadioButton mNetwork = new RadioButton();
        private readonly RadioButton mAttached = new RadioButton();
        private readonly RadioButton mWsa = new RadioButton();
        private readonly TextBox mAddress = new TextBox();
        private readonly TextBox mLog = new TextBox();
        private readonly Button mInstall = new Button();
        private readonly LinkLabel mHelp = new LinkLabel();

        public MainForm()
        {
            Text = "KDTube " + BuildInfo.Version + " for TV and phone: installer";
            Font = new Font("Segoe UI", 9.75f);
            AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(620, 520);
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);

            Label title = new Label();
            title.Text = "Install KDTube on a TV, a stick, a phone or a tablet";
            title.Font = new Font("Segoe UI", 14f, FontStyle.Bold);
            title.SetBounds(20, 16, 580, 30);

            Label about = new Label();
            about.Text = "KDTube is an Android app. This setup sends it from the PC to your device and starts it there. " +
                    "Nothing is installed on Windows.";
            about.SetBounds(20, 52, 580, 40);

            mNetwork.Text = "A device in my network (Android TV, Google TV, Fire TV). Its address:";
            mNetwork.SetBounds(20, 104, 470, 24);
            mNetwork.Checked = true;

            mAddress.SetBounds(40, 132, 220, 26);
            mAddress.Text = "";

            Label example = new Label();
            example.Text = "e.g. 192.168.1.50";
            example.ForeColor = SystemColors.GrayText;
            example.SetBounds(268, 135, 200, 22);

            mAttached.Text = "A phone or tablet connected with a USB cable, or a running Android emulator";
            mAttached.SetBounds(20, 168, 580, 24);

            mWsa.Text = "Windows Subsystem for Android on this PC (where it is still installed)";
            mWsa.SetBounds(20, 196, 580, 24);

            mHelp.Text = "How do I switch on debugging on the device and find its address?";
            mHelp.SetBounds(20, 230, 580, 22);
            mHelp.LinkClicked += delegate { ShowHelp(); };

            mLog.Multiline = true;
            mLog.ReadOnly = true;
            mLog.ScrollBars = ScrollBars.Vertical;
            mLog.BackColor = SystemColors.Window;
            mLog.SetBounds(20, 262, 580, 196);

            mInstall.Text = "Install";
            mInstall.SetBounds(480, 470, 120, 34);
            mInstall.Click += delegate { StartInstall(); };
            AcceptButton = mInstall;

            Controls.AddRange(new Control[] { title, about, mNetwork, mAddress, example, mAttached, mWsa, mHelp, mLog, mInstall });

            mNetwork.CheckedChanged += delegate { mAddress.Enabled = mNetwork.Checked; };
            Shown += delegate { mAddress.Focus(); };
        }

        private void ShowHelp()
        {
            MessageBox.Show(this,
                    "On the device (once):\n\n" +
                    "Android TV / Google TV\n" +
                    "  Settings > System > About: press 'Android TV OS build' 7 times.\n" +
                    "  Settings > System > Developer options: switch on 'USB debugging'\n" +
                    "  (and 'Network debugging' or 'Wireless debugging' if it is listed).\n\n" +
                    "Fire TV\n" +
                    "  Settings > My Fire TV > About: press the name of the device 7 times.\n" +
                    "  Settings > My Fire TV > Developer Options: switch on 'ADB Debugging'.\n\n" +
                    "The address\n" +
                    "  Settings > Network (or About > Network / Status): 'IP address',\n" +
                    "  four numbers like 192.168.1.50.\n\n" +
                    "The PC and the device have to be in the same network. The first time the device asks\n" +
                    "to allow debugging from this computer: choose 'Always allow' and OK.",
                    "Preparing the device", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        private void StartInstall()
        {
            string device = mNetwork.Checked ? mAddress.Text.Trim() : mWsa.Checked ? "wsa" : "";

            if (mNetwork.Checked && device.Length == 0)
            {
                MessageBox.Show(this, "Type the address of the device first.", Text, MessageBoxButtons.OK, MessageBoxIcon.Information);
                mAddress.Focus();
                return;
            }

            mInstall.Enabled = false;
            mLog.Clear();

            Installer installer = new Installer();
            installer.Log = delegate(string message) { BeginInvoke((MethodInvoker) delegate { AppendLog(message); }); };
            installer.Confirm = delegate(string question)
            {
                return (bool) Invoke((Func<bool>) delegate
                {
                    return MessageBox.Show(this, question, Text, MessageBoxButtons.YesNo, MessageBoxIcon.Warning) == DialogResult.Yes;
                });
            };

            Thread worker = new Thread(delegate()
            {
                string error = null;

                try
                {
                    installer.Run(device);
                }
                catch (SetupException e)
                {
                    error = e.Message;
                }
                catch (Exception e)
                {
                    error = e.Message;
                }

                BeginInvoke((MethodInvoker) delegate
                {
                    mInstall.Enabled = true;

                    if (error != null)
                    {
                        AppendLog("");
                        AppendLog("NOT INSTALLED: " + error);
                        MessageBox.Show(this, error, "KDTube was not installed", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    }
                    else
                    {
                        MessageBox.Show(this, "KDTube is on the device now. It is in the list of the apps as \"KDTube\".", Text,
                                MessageBoxButtons.OK, MessageBoxIcon.Information);
                    }
                });
            });
            worker.IsBackground = true;
            worker.Start();
        }

        private void AppendLog(string message)
        {
            mLog.AppendText(message + Environment.NewLine);
        }
    }

    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            string device = "";
            string logFile = null;
            bool silent = false;
            bool launch = true;
            bool replace = false;

            foreach (string arg in args)
            {
                string lower = arg.ToLowerInvariant();

                if (lower == "/silent") { silent = true; }
                else if (lower == "/wsa") { device = "wsa"; }
                else if (lower == "/nolaunch") { launch = false; }
                else if (lower == "/replace") { replace = true; }
                else if (lower.StartsWith("/device:")) { device = arg.Substring(8); }
                else if (lower.StartsWith("/log:")) { logFile = arg.Substring(5); }
            }

            if (!silent)
            {
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new MainForm());
                return 0;
            }

            StringBuilder log = new StringBuilder();
            Installer installer = new Installer();
            installer.Launch = launch;
            installer.Log = delegate(string message) { log.AppendLine(message); };
            installer.Confirm = delegate { return replace; };
            int result = 0;

            try
            {
                installer.Run(device);
            }
            catch (Exception e)
            {
                log.AppendLine("NOT INSTALLED: " + e.Message);
                result = 1;
            }

            if (logFile != null)
            {
                File.WriteAllText(logFile, log.ToString());
            }

            return result;
        }
    }
}
