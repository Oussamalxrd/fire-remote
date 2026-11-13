// Remote buttons dialog: maps the Fire TV remote's app buttons through FireKeys (com.oussama.firekeys) on the Stick.
// Talks to FireKeys over adb: "am broadcast ... GET" to read the map, "... SET" to change one button.
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

namespace FireRemote
{
    sealed class ButtonsDialog : Form
    {
        const string Receiver = "com.oussama.firekeys/.ConfigReceiver";
        const string Normal = "(normal)";

        // FireKeys button id, label shown here
        static readonly string[][] Buttons =
        {
            new[] { "Netflix", "Netflix" },
            new[] { "PrimeVideo", "Prime Video" },
            new[] { "YouTube", "YouTube" },
            new[] { "Disney", "Disney+" },
            new[] { "TV", "TV" },
            new[] { "TV.long", "TV (hold)" },   // only TV can be held: app buttons send down+up at once
        };

        // FireKeys built-in actions (instead of an app)
        sealed class ActionItem
        {
            public readonly string Target, Label;
            public ActionItem(string target, string label) { Target = target; Label = label; }
            public override string ToString() { return Label; }
        }

        static readonly ActionItem[] Actions =
        {
            new ActionItem("firekeys:lastapp", "Action: last app (switch back)"),
            new ActionItem("firekeys:sleep", "Action: sleep (Stick standby)"),
        };

        sealed class AppItem
        {
            public readonly AppEntry App;
            public AppItem(AppEntry a) { App = a; }
            public override string ToString()
            {
                return App.Name == App.Package ? App.Package : App.Name + "   (" + App.Package + ")";
            }
        }

        readonly float s;
        readonly Session session;
        readonly ComboBox[] boxes = new ComboBox[Buttons.Length];
        readonly Label status;
        readonly FlatButton save;
        Dictionary<string, string> current = new Dictionary<string, string>();
        List<AppEntry> apps;
        bool busy;

        public ButtonsDialog(float scale, Session session, List<AppEntry> apps)
        {
            s = scale;
            this.session = session;
            this.apps = apps;
            Text = "Remote buttons";
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

            int y = S(14);
            for (int i = 0; i < Buttons.Length; i++)
            {
                Controls.Add(new Label
                {
                    Bounds = new Rectangle(S(14), y, S(90), S(28)),
                    Text = Buttons[i][1],
                    ForeColor = Theme.Text,
                    BackColor = Theme.Bg,
                    TextAlign = ContentAlignment.MiddleLeft,
                });
                var box = new ComboBox
                {
                    Bounds = new Rectangle(S(108), y + S(2), S(258), S(26)),
                    DropDownStyle = ComboBoxStyle.DropDown,   // pick an app, or type a package / https link
                    FlatStyle = FlatStyle.Flat,
                    BackColor = Theme.Field,
                    ForeColor = Theme.Text,
                    MaxDropDownItems = 14,
                    Enabled = false,
                };
                boxes[i] = box;
                Controls.Add(box);
                y += S(36);
            }

            status = new Label
            {
                Bounds = new Rectangle(S(14), y + S(4), S(352), S(40)),
                ForeColor = Theme.SubText,
                BackColor = Theme.Bg,
                Text = "Reading FireKeys\u2026",
                TextAlign = ContentAlignment.TopLeft,
            };
            Controls.Add(status);

            int by = y + S(62);
            ClientSize = new Size(S(380), by + S(48));
            var cancel = new FlatButton { Text = "Close", Bounds = new Rectangle(S(14), by, S(172), S(34)) };
            cancel.Click += delegate { Close(); };
            save = new FlatButton { Text = "Save", Accent = true, Bounds = new Rectangle(S(194), by, S(172), S(34)) };
            save.Click += delegate { Save(); };
            Controls.Add(cancel);
            Controls.Add(save);
        }

        int S(float v) { return (int)Math.Round(v * s); }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            if (apps == null)
            {
                session.LoadApps(delegate (List<AppEntry> list)
                {
                    Ui(delegate { apps = list ?? new List<AppEntry>(); Fill(); });
                });
            }
            Reload();
        }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            base.OnKeyDown(e);
            if (e.KeyCode == Keys.Escape) { e.SuppressKeyPress = true; Close(); }
        }

        // ---- adb

        void Reload()
        {
            Run(delegate { ReadAndShow(null); });
        }

        /// Worker thread: reads the map from FireKeys and shows it.
        void ReadAndShow(string doneText)
        {
            var r = Adb("shell am broadcast -n " + Receiver + " -a com.oussama.firekeys.GET");
            var map = ParseMap(r);
            Ui(delegate
            {
                if (map == null)
                {
                    SetStatus("FireKeys isn't installed or not set up on the TV.", Theme.Amber);
                    return;
                }
                current = map;
                Fill();
                string svc = Field(r, "service"), src = Field(r, "source");
                bool ok = svc == "running" && Field(r, "readLogs") == "true" && Field(r, "overlay") == "true";
                bool sleepOff = map.ContainsValue("firekeys:sleep") && Field(r, "admin") != "true";
                SetStatus(doneText ?? ("FireKeys: " + (svc ?? "?") + "  \u00B7  " + (src ?? "?")
                    + (ok ? "" : "\nPermission missing \u2014 re-run the FireKeys install commands.")
                    + (sleepOff ? "\nSleep needs: adb shell dpm set-active-admin com.oussama.firekeys/.AdminReceiver" : "")),
                    ok && !sleepOff ? Theme.SubText : Theme.Amber);
            });
        }

        void Save()
        {
            if (busy) return;
            var changes = new List<KeyValuePair<string, string>>();
            for (int i = 0; i < Buttons.Length; i++)
            {
                string id = Buttons[i][0];
                string want = TargetOf(boxes[i]);
                string have;
                current.TryGetValue(id, out have);
                if (!string.Equals(want ?? "", have ?? "", StringComparison.Ordinal))
                    changes.Add(new KeyValuePair<string, string>(id, want));
            }
            if (changes.Count == 0) { SetStatus("Nothing changed.", Theme.SubText); return; }

            SetStatus("Saving\u2026", Theme.SubText);
            Run(delegate
            {
                foreach (var c in changes)
                {
                    string args = "shell am broadcast -n " + Receiver + " -a com.oussama.firekeys.SET --es button " + c.Key;
                    if (c.Value != null) args += " --es target '" + c.Value + "'";
                    Adb(args);
                }
                ReadAndShow("Saved \u2014 press the button on the remote to try it.");
            });
        }

        string Adb(string args)
        {
            var adb = session.Adb;
            var serial = session.Serial;
            if (adb == null || serial == null) return "";
            return adb.On(serial, args, 15000).All;
        }

        void Run(Action work)
        {
            busy = true;
            save.Enabled = false;
            ThreadPool.QueueUserWorkItem(delegate
            {
                try { work(); }
                catch (Exception ex) { Ui(delegate { SetStatus("Error: " + ex.Message, Theme.Red); }); }
                finally { Ui(delegate { busy = false; save.Enabled = true; }); }
            });
        }

        // ---- UI

        void Fill()
        {
            var byName = StringComparer.CurrentCultureIgnoreCase;
            var list = (apps ?? new List<AppEntry>())
                .Where(a => !a.IsSystem && a.Package != "com.oussama.firekeys")
                .OrderBy(a => a.Name, byName).ToList();
            for (int i = 0; i < Buttons.Length; i++)
            {
                var box = boxes[i];
                box.BeginUpdate();
                box.Items.Clear();
                box.Items.Add(Normal);
                foreach (var act in Actions) box.Items.Add(act);
                foreach (var a in list) box.Items.Add(new AppItem(a));
                box.EndUpdate();

                string target;
                current.TryGetValue(Buttons[i][0], out target);
                if (string.IsNullOrEmpty(target)) box.SelectedIndex = 0;
                else
                {
                    var action = Actions.FirstOrDefault(x => x.Target == target);
                    var match = list.FirstOrDefault(a => a.Package == target);
                    if (action != null) box.SelectedItem = action;
                    else if (match != null) box.SelectedIndex = box.Items.IndexOf(box.Items.OfType<AppItem>().First(x => x.App == match));
                    else box.Text = target;   // URL, component, or an app not in the list
                }
                box.Enabled = true;
            }
        }

        static string TargetOf(ComboBox box)
        {
            var item = box.SelectedItem as AppItem;
            if (item != null && box.Text == item.ToString()) return item.App.Package;
            var act = box.SelectedItem as ActionItem;
            if (act != null && box.Text == act.ToString()) return act.Target;
            string t = (box.Text ?? "").Trim().Replace("'", "").Replace("\"", "");
            if (t.Length == 0 || t == Normal) return null;
            var m = Regex.Match(t, @"\(([A-Za-z0-9_.]+)\)\s*$");   // "Name   (package)" typed or pasted
            return m.Success ? m.Groups[1].Value : t;
        }

        void SetStatus(string text, Color color)
        {
            status.ForeColor = color;
            status.Text = text;
        }

        void Ui(Action a)
        {
            if (IsDisposed) return;
            try
            {
                if (InvokeRequired) BeginInvoke(a);
                else a();
            }
            catch (ObjectDisposedException) { }
            catch (InvalidOperationException) { }
        }

        // ---- FireKeys reply:  Broadcast completed: result=-1, data="{"map":{"YouTube":"com.x"},"service":"running",...}"

        static Dictionary<string, string> ParseMap(string reply)
        {
            var m = Regex.Match(reply ?? "", "\"map\":\\{(.*?)\\}");
            if (!m.Success) return null;
            var map = new Dictionary<string, string>();
            foreach (Match p in Regex.Matches(m.Groups[1].Value, "\"([^\"]+)\":\"((?:[^\"\\\\]|\\\\.)*)\""))
                map[p.Groups[1].Value] = p.Groups[2].Value.Replace("\\/", "/");
            return map;
        }

        static string Field(string reply, string name)
        {
            var m = Regex.Match(reply ?? "", "\"" + name + "\":\"?([^\",}]*)");
            return m.Success ? m.Groups[1].Value : null;
        }
    }
}
