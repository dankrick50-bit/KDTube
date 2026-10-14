// The setup of KDTube for Windows (the PC edition). One file, no installer framework:
// compiled with the C# compiler that is a part of Windows (see packaging/desktop/build-desktop.js).
//
// Installs for the current user, no administrator rights needed:
//   %LOCALAPPDATA%\Programs\KDTube        the app (the settings and the sign-in live in %APPDATA%\KDTube)
//   Start menu and (optionally) desktop shortcuts, an entry in "Installed apps" with an uninstaller.
//
//   KDTube-...-setup.exe                  the window
//   KDTube-...-setup.exe /silent          no window. Also: /dir:<folder> /nodesktop /nolaunch /log:<file>
//   Uninstall.exe /uninstall [/silent] [/purge]      /purge also removes the settings and the sign-in
//
// NOTE: C# 5 only (the compiler of .NET Framework 4).
using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("KDTube Setup")]
[assembly: AssemblyProduct("KDTube")]
[assembly: AssemblyCompany("KDTube")]

namespace KDTubeDesktopSetup
{
    internal static class Product
    {
        public const string Name = "KDTube";
        public const string ExeName = "KDTube.exe";
        public const string UninstallerName = "Uninstall.exe";
        public const string UninstallKey = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\KDTube";
        public const string Payload = "payload.zip";

        public static string Version
        {
            get
            {
                Version version = Assembly.GetExecutingAssembly().GetName().Version;
                return version.Major + "." + version.Minor;
            }
        }

        public static string DefaultDir
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", Name); }
        }

        public static string DataDir
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), Name); }
        }

        public static string StartMenuLink
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), Name + ".lnk"); }
        }

        public static string DesktopLink
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), Name + ".lnk"); }
        }
    }

    internal sealed class Options
    {
        public bool Silent;
        public bool Uninstall;
        public bool Purge;
        public bool DesktopShortcut = true;
        public bool Launch = true;
        public string Dir = Product.DefaultDir;
        public string LogFile;

        public static Options Parse(string[] args)
        {
            Options options = new Options();

            foreach (string arg in args)
            {
                string lower = arg.ToLowerInvariant();
                if (lower == "/silent" || lower == "/s") options.Silent = true;
                else if (lower == "/uninstall") options.Uninstall = true;
                else if (lower == "/purge") options.Purge = true;
                else if (lower == "/nodesktop") options.DesktopShortcut = false;
                else if (lower == "/nolaunch") options.Launch = false;
                else if (lower.StartsWith("/dir:")) options.Dir = arg.Substring(5).Trim('"');
                else if (lower.StartsWith("/log:")) options.LogFile = arg.Substring(5).Trim('"');
            }

            return options;
        }
    }

    internal sealed class Worker
    {
        private readonly Options mOptions;
        public Action<string> OnStatus;
        public Action<int> OnProgress;

        public Worker(Options options)
        {
            mOptions = options;
        }

        private void Status(string text)
        {
            if (mOptions.LogFile != null)
            {
                try
                {
                    File.AppendAllText(mOptions.LogFile, DateTime.Now.ToString("HH:mm:ss") + " " + text + Environment.NewLine);
                }
                catch (IOException)
                {
                    // the log is a nicety
                }
            }

            if (OnStatus != null) OnStatus(text);
        }

        private void Progress(int percent)
        {
            if (OnProgress != null) OnProgress(percent);
        }

        /// <returns>false if the app is still running and the user doesn't want it closed</returns>
        public static bool CloseRunningApp(string dir, bool ask)
        {
            string exe = Path.Combine(dir, Product.ExeName);

            for (int attempt = 0; attempt < 3; attempt++)
            {
                bool found = false;

                foreach (Process process in Process.GetProcessesByName(Path.GetFileNameWithoutExtension(Product.ExeName)))
                {
                    try
                    {
                        if (!string.Equals(process.MainModule.FileName, exe, StringComparison.OrdinalIgnoreCase)) continue;

                        if (!found && ask && attempt == 0 && MessageBox.Show(Product.Name + " is running. Close it and continue?", Product.Name + " Setup",
                                MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK)
                        {
                            return false;
                        }

                        found = true;
                        if (attempt == 0) process.CloseMainWindow();
                        else process.Kill();
                    }
                    catch (Exception)
                    {
                        // a process of another user, or one that is gone already
                    }
                }

                if (!found) return true;
                Thread.Sleep(1500);
            }

            return true;
        }

        public void Install()
        {
            string dir = mOptions.Dir;
            Status("Installing to " + dir);

            // What is left of the version before: the files of the app only, the settings live elsewhere
            if (Directory.Exists(dir))
            {
                Status("Removing the previous version");
                DeleteFolder(dir, 3);
            }

            Directory.CreateDirectory(dir);

            using (Stream payload = Assembly.GetExecutingAssembly().GetManifestResourceStream(Product.Payload))
            {
                if (payload == null) throw new InvalidOperationException("This setup has no files inside (" + Product.Payload + ")");

                using (ZipArchive archive = new ZipArchive(payload, ZipArchiveMode.Read))
                {
                    long total = 0;
                    foreach (ZipArchiveEntry entry in archive.Entries) total += entry.Length;

                    long done = 0;
                    byte[] buffer = new byte[256 * 1024];
                    string root = Path.GetFullPath(dir) + Path.DirectorySeparatorChar;

                    foreach (ZipArchiveEntry entry in archive.Entries)
                    {
                        string target = Path.GetFullPath(Path.Combine(dir, entry.FullName.Replace('/', Path.DirectorySeparatorChar)));
                        // Nothing gets out of the folder of the app, whatever a name in the archive says
                        if (!target.StartsWith(root, StringComparison.OrdinalIgnoreCase)) continue;

                        if (entry.FullName.EndsWith("/"))
                        {
                            Directory.CreateDirectory(target);
                            continue;
                        }

                        Directory.CreateDirectory(Path.GetDirectoryName(target));

                        using (Stream input = entry.Open())
                        using (FileStream output = new FileStream(target, FileMode.Create, FileAccess.Write))
                        {
                            int read;
                            while ((read = input.Read(buffer, 0, buffer.Length)) > 0)
                            {
                                output.Write(buffer, 0, read);
                                done += read;
                                Progress(total > 0 ? (int) (done * 95 / total) : 0);
                            }
                        }
                    }

                    Status("Copied " + archive.Entries.Count + " files");
                }
            }

            string exe = Path.Combine(dir, Product.ExeName);
            if (!File.Exists(exe)) throw new FileNotFoundException("The app isn't where it should be", exe);

            string uninstaller = Path.Combine(dir, Product.UninstallerName);
            File.Copy(Application.ExecutablePath, uninstaller, true);

            Status("Creating the shortcuts");
            CreateShortcut(Product.StartMenuLink, exe, dir);
            if (mOptions.DesktopShortcut) CreateShortcut(Product.DesktopLink, exe, dir);
            else TryDelete(Product.DesktopLink);

            using (RegistryKey key = Registry.CurrentUser.CreateSubKey(Product.UninstallKey))
            {
                key.SetValue("DisplayName", Product.Name);
                key.SetValue("DisplayVersion", Product.Version);
                key.SetValue("Publisher", Product.Name);
                key.SetValue("DisplayIcon", exe);
                key.SetValue("InstallLocation", dir);
                key.SetValue("UninstallString", "\"" + uninstaller + "\" /uninstall");
                key.SetValue("QuietUninstallString", "\"" + uninstaller + "\" /uninstall /silent");
                key.SetValue("NoModify", 1, RegistryValueKind.DWord);
                key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
                key.SetValue("EstimatedSize", (int) (GetFolderSize(dir) / 1024), RegistryValueKind.DWord);
            }

            Progress(100);
            Status("Done");

            if (mOptions.Launch)
            {
                ProcessStartInfo start = new ProcessStartInfo(exe);
                start.WorkingDirectory = dir;
                // NOTE: the app starts with the rights and the environment of the user, not with what a tool that ran the setup had
                start.UseShellExecute = true;
                Process.Start(start);
            }
        }

        public void Uninstall()
        {
            string dir = Path.GetDirectoryName(Application.ExecutablePath);
            Status("Removing " + Product.Name + " from " + dir);

            TryDelete(Product.StartMenuLink);
            TryDelete(Product.DesktopLink);
            Registry.CurrentUser.DeleteSubKeyTree(Product.UninstallKey, false);

            if (mOptions.Purge && Directory.Exists(Product.DataDir))
            {
                Status("Removing the settings and the sign-in");
                DeleteFolder(Product.DataDir, 2);
            }

            // NOTE: only a folder that holds the app. Never anything else, wherever this program was copied to.
            if (!File.Exists(Path.Combine(dir, Product.ExeName)) || dir.IndexOf('"') >= 0)
            {
                Status("The app isn't in this folder: nothing more to remove");
                return;
            }

            // A program can't delete the folder it runs from. The command line of Windows does it a moment
            // after this program is gone (a few tries: the files are free as soon as it has ended).
            string command = "for /l %i in (1,1,8) do @(ping -n 2 127.0.0.1 >nul & rmdir /s /q \"" + dir + "\" 2>nul & if not exist \"" + dir + "\" exit)";
            ProcessStartInfo start = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "cmd.exe"), "/d /s /c \"" + command + "\"");
            start.WorkingDirectory = Path.GetTempPath();
            start.UseShellExecute = false;
            start.CreateNoWindow = true;
            Process.Start(start);
            Status("Removed");
        }

        private static void DeleteFolder(string dir, int attempts)
        {
            for (int i = 0; i < attempts && Directory.Exists(dir); i++)
            {
                try
                {
                    Directory.Delete(dir, true);
                }
                catch (IOException)
                {
                    Thread.Sleep(700);
                }
                catch (UnauthorizedAccessException)
                {
                    Thread.Sleep(700);
                }
            }
        }

        private static void TryDelete(string file)
        {
            try
            {
                if (File.Exists(file)) File.Delete(file);
            }
            catch (IOException)
            {
                // a shortcut that stays is not worth failing for
            }
            catch (UnauthorizedAccessException)
            {
            }
        }

        private static long GetFolderSize(string dir)
        {
            long size = 0;
            foreach (string file in Directory.GetFiles(dir, "*", SearchOption.AllDirectories)) size += new FileInfo(file).Length;
            return size;
        }

        /// <summary>Through the scripting host of Windows: there's no class for shortcuts in .NET</summary>
        private static void CreateShortcut(string link, string target, string workingDir)
        {
            Type shellType = Type.GetTypeFromProgID("WScript.Shell");
            if (shellType == null) return;

            object shell = Activator.CreateInstance(shellType);
            object shortcut = shellType.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] {link});
            Type type = shortcut.GetType();
            type.InvokeMember("TargetPath", BindingFlags.SetProperty, null, shortcut, new object[] {target});
            type.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, shortcut, new object[] {workingDir});
            type.InvokeMember("Description", BindingFlags.SetProperty, null, shortcut, new object[] {"YouTube without the clutter, and your own video and music files"});
            type.InvokeMember("IconLocation", BindingFlags.SetProperty, null, shortcut, new object[] {target + ",0"});
            type.InvokeMember("Save", BindingFlags.InvokeMethod, null, shortcut, null);
        }
    }

    internal sealed class SetupForm : Form
    {
        private static readonly Color Background = Color.FromArgb(0x0F, 0x14, 0x19);
        private static readonly Color Surface = Color.FromArgb(0x19, 0x20, 0x2A);
        private static readonly Color Accent = Color.FromArgb(0x4C, 0x8D, 0xFF);
        private static readonly Color TextColor = Color.FromArgb(0xF2, 0xF5, 0xF9);
        private static readonly Color Secondary = Color.FromArgb(0x9A, 0xA7, 0xB5);
        private readonly Options mOptions;
        private readonly Label mStatus;
        private readonly ProgressBar mProgress;
        private readonly Button mInstall;
        private readonly CheckBox mDesktop;
        private readonly CheckBox mLaunch;
        private bool mIsDone;

        public SetupForm(Options options)
        {
            mOptions = options;
            Text = Product.Name + " Setup";
            Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            AutoScaleMode = AutoScaleMode.Dpi;
            ClientSize = new Size(520, 330);
            BackColor = Background;
            ForeColor = TextColor;
            Font = new Font("Segoe UI", 10f);

            Label title = new Label();
            title.Text = Product.Name + " " + Product.Version;
            title.Font = new Font("Segoe UI Semibold", 20f);
            title.SetBounds(32, 26, 456, 44);
            Controls.Add(title);

            Label about = new Label();
            about.Text = "YouTube made for the big screen, without adverts, with picture and sound options. And a player for the video and music files of this computer.";
            about.ForeColor = Secondary;
            about.SetBounds(34, 76, 452, 64);
            Controls.Add(about);

            mDesktop = AddCheckBox("Put a shortcut on the desktop", 150, options.DesktopShortcut);
            mLaunch = AddCheckBox("Start " + Product.Name + " when the setup is done", 180, options.Launch);

            mStatus = new Label();
            mStatus.Text = "Installs for you only, into " + options.Dir;
            mStatus.ForeColor = Secondary;
            mStatus.AutoEllipsis = true;
            mStatus.SetBounds(34, 222, 452, 22);
            Controls.Add(mStatus);

            mProgress = new ProgressBar();
            mProgress.SetBounds(34, 248, 452, 8);
            mProgress.Style = ProgressBarStyle.Continuous;
            Controls.Add(mProgress);

            mInstall = new Button();
            mInstall.Text = "Install";
            mInstall.FlatStyle = FlatStyle.Flat;
            mInstall.FlatAppearance.BorderSize = 0;
            mInstall.BackColor = Accent;
            mInstall.ForeColor = Color.White;
            mInstall.Font = new Font("Segoe UI Semibold", 10.5f);
            mInstall.SetBounds(346, 276, 140, 36);
            mInstall.Click += OnInstallClick;
            Controls.Add(mInstall);
            AcceptButton = mInstall;
        }

        private CheckBox AddCheckBox(string text, int top, bool isChecked)
        {
            CheckBox box = new CheckBox();
            box.Text = text;
            box.Checked = isChecked;
            box.FlatStyle = FlatStyle.Flat;
            box.BackColor = Background;
            box.SetBounds(36, top, 450, 26);
            Controls.Add(box);
            return box;
        }

        private void OnInstallClick(object sender, EventArgs e)
        {
            if (mIsDone)
            {
                Close();
                return;
            }

            if (!Worker.CloseRunningApp(mOptions.Dir, true)) return;

            mOptions.DesktopShortcut = mDesktop.Checked;
            mOptions.Launch = mLaunch.Checked;
            mInstall.Enabled = mDesktop.Enabled = mLaunch.Enabled = false;

            Worker worker = new Worker(mOptions);
            worker.OnStatus = delegate(string text) { BeginInvoke((MethodInvoker) delegate { mStatus.Text = text; }); };
            worker.OnProgress = delegate(int percent) { BeginInvoke((MethodInvoker) delegate { mProgress.Value = Math.Max(0, Math.Min(100, percent)); }); };

            Thread thread = new Thread(delegate()
            {
                string error = null;

                try
                {
                    worker.Install();
                }
                catch (Exception ex)
                {
                    error = ex.Message;
                }

                BeginInvoke((MethodInvoker) delegate
                {
                    mIsDone = true;
                    mInstall.Enabled = true;
                    mInstall.Text = "Close";

                    if (error != null)
                    {
                        mStatus.ForeColor = Color.FromArgb(0xFF, 0x8A, 0x80);
                        mStatus.Text = "Not installed: " + error;
                    }
                    else
                    {
                        mStatus.Text = Product.Name + " is installed. It's in the Start menu.";
                        if (mOptions.Launch) Close();
                    }
                });
            });
            thread.IsBackground = true;
            thread.Start();
        }
    }

    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            Options options = Options.Parse(args);
            Worker worker = new Worker(options);

            try
            {
                if (options.Uninstall)
                {
                    string dir = Path.GetDirectoryName(Application.ExecutablePath);

                    if (!options.Silent)
                    {
                        Application.EnableVisualStyles();

                        if (MessageBox.Show("Remove " + Product.Name + " from this computer?", Product.Name, MessageBoxButtons.OKCancel,
                                MessageBoxIcon.Question) != DialogResult.OK)
                        {
                            return 1;
                        }

                        options.Purge = MessageBox.Show("Also remove its settings and the YouTube sign-in?", Product.Name, MessageBoxButtons.YesNo,
                                MessageBoxIcon.Question, MessageBoxDefaultButton.Button2) == DialogResult.Yes;
                    }

                    if (!Worker.CloseRunningApp(dir, !options.Silent)) return 1;
                    worker.Uninstall();
                    return 0;
                }

                if (options.Silent)
                {
                    Worker.CloseRunningApp(options.Dir, false);
                    worker.Install();
                    return 0;
                }
            }
            catch (Exception ex)
            {
                if (options.LogFile != null) File.AppendAllText(options.LogFile, "ERROR: " + ex + Environment.NewLine);
                if (!options.Silent) MessageBox.Show(ex.Message, Product.Name + " Setup", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new SetupForm(options));
            return 0;
        }
    }
}
