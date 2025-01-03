using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Windows.UI.Notifications;
using System.Net.Http;
using System.Text.RegularExpressions;
using System.Threading;
using Flurl.Http;
using log4net;
using Microsoft.Toolkit.Uwp.Notifications;
using Newtonsoft.Json.Linq;
using Octokit;
using Application = System.Windows.Application;

namespace OTPAutoForward.ServiceHandler
{
    public static class UpdateHandler
    {
        private static readonly ILog Log = LogManager.GetLogger(typeof(UpdateHandler));

        private static readonly string Repository = App.AppSettings.Repository;

        private static readonly string RepositoryOwner = App.AppSettings.RepositoryOwner;

        private static readonly string ReleasesSource = App.AppSettings.ReleasesSource;

        private static readonly string CurrentVersion =
            App.AppSettings.CurrentVersion.ToLower().Replace("v", "").Replace("ver", "");

        private static string _latestVersion;

        private static string _downloadUrl;

        static UpdateHandler()
        {
            ToastNotificationManagerCompat.OnActivated += async toastArgs =>
            {
                await HandleToastActivationAsync(toastArgs);
            };
        }

        /// <summary>
        /// 处理 Toast 弹窗的按钮点击事件, 选择 "确定更新" 时下载更新包并进行安装 
        /// </summary>
        /// <param name="toastArgs">"确定更新" 或 "暂不更新" </param>
        private static async Task HandleToastActivationAsync(ToastNotificationActivatedEventArgsCompat toastArgs)
        {
            var args = ToastArguments.Parse(toastArgs.Argument);

            // 点击事件只针对特定标识的 Toast 弹窗生效
            if (!args.Contains("source") || args["source"] != "UpdateHandler")
                return;
            if (args["action"] != "update") return;

            // 点击 "确定更新" 时开始下载安装包
            var filePath = await DownloadFileAsync();

            if (filePath == null)
            {
                ShowToastNotification("下载更新包失败, 请稍后再试");
                return;
            }

            StartInstaller(filePath);
        }

        /// <summary>
        /// 托盘菜单栏 "检查更新" 逻辑
        /// </summary>
        public static async Task CheckUpdatesAsync()
        {
            if (string.IsNullOrWhiteSpace(ReleasesSource))
            {
                // 未设置发布仓库源的时候无法检查更新
                ShowToastNotification("暂无更新");
                return;
            }

            try
            {
                var (latestVersion, downloadUrl) = await GetLatestReleaseAsync();

                if (string.IsNullOrEmpty(latestVersion) || string.IsNullOrEmpty(downloadUrl))
                {
                    ShowToastNotification($"暂无更新\n当前版本: {CurrentVersion}");
                    return;
                }

                if (latestVersion != CurrentVersion)
                {
                    _latestVersion = latestVersion;
                    _downloadUrl = downloadUrl;
                    ShowUpdateNotification(latestVersion);
                }
                else
                {
                    ShowToastNotification($"当前版本已是最新版\n当前版本: {CurrentVersion}");
                }
            }
            catch (Exception ex)
            {
                Log.Error($"检查更新时发生异常: {ex.Message}");
                ShowToastNotification("检查更新失败, 请稍后再试");
            }
        }

        /// <summary>
        /// 获取最新版本
        /// </summary>
        /// <returns>最新版本号, 安装包下载地址</returns>
        private static Task<(string, string)> GetLatestReleaseAsync()
        {
            var releaseSource = ReleasesSource.ToLower();

            if (releaseSource.Contains("gitee"))
            {
                return GetGiteeReleaseAsync();
            }

            if (releaseSource.Contains("github"))
            {
                return GetGitHubReleaseAsync();
            }

            return null;
        }

        /// <summary>
        /// 从 GitHub 仓库源获取最新版本
        /// </summary>
        /// <returns>最新版本号, 安装包下载地址</returns>
        private static async Task<(string, string)> GetGitHubReleaseAsync()
        {
            try
            {
                var client = new GitHubClient(new ProductHeaderValue("GitHubClient"));

                var latestRelease = await client.Repository.Release.GetLatest(RepositoryOwner, Repository);

                var latestVersion = latestRelease.TagName.ToLower()
                    .Replace("v", "").Replace("ver", "");
                var assets = latestRelease.Assets;

                // 从 assets 属性中获取 exe 安装包的下载地址
                var downloadUrl = assets.Where(x => x.BrowserDownloadUrl.Contains("exe"))
                    .Select(x => x.BrowserDownloadUrl)
                    .FirstOrDefault();

                return (latestVersion, downloadUrl);
            }
            catch (Exception e)
            {
                Log.Error($"从 GitHub 获取更新包失败: {e.Message}");
                Console.WriteLine($"从 GitHub 获取更新包失败: {e.Message}");
                return (null, null);
            }
        }

        /// <summary>
        /// 从 Gitee 仓库源获取最新版本
        /// </summary>
        /// <returns>最新版本号, 安装包下载地址</returns>
        private static async Task<(string, string)> GetGiteeReleaseAsync()
        {
            const string giteeUrl = "https://gitee.com/api/v5/repos/";
            const string domain = "/releases/";
            var owner = RepositoryOwner;
            var repo = Repository;
            var id = await GetGiteeReleaseIdAsync();

            if (id == null)
            {
                return (null, null);
            }

            var requestUrlString = giteeUrl + owner + "/" + repo + domain + id;

            try
            {
                var requestUrl = new Uri(requestUrlString);
                using (var client = new HttpClient())
                {
                    var response = await client.GetAsync(requestUrl);
                    response.EnsureSuccessStatusCode();
                    var responseBody = await response.Content.ReadAsStringAsync();

                    var releaseObject = JObject.Parse(responseBody);

                    var latestVersion = releaseObject["tag_name"]?.ToString().ToLower()
                        .Replace("v", "").Replace("ver", "");
                    var assets = releaseObject["assets"] as JArray;

                    // 从 assets 属性中获取 exe 安装包的下载地址
                    var downloadUrl = assets?
                        .FirstOrDefault(x => x["browser_download_url"]
                            .ToString()
                            .Contains("exe"))?["browser_download_url"]
                        ?.ToString();

                    return (latestVersion, downloadUrl);
                }
            }
            catch (Exception e)
            {
                Log.Error($"从 Gitee 获取更新包失败: {e.Message}");
                Console.WriteLine($"从 Gitee 获取更新包失败: {e.Message}");
                return (null, null);
            }
        }

        /// <summary>
        /// 从 Gitee 仓库源获取最新版本的 ID
        /// </summary>
        /// <returns>最新发行版的 ID 号</returns>
        private static async Task<int?> GetGiteeReleaseIdAsync()
        {
            const string giteeUrl = "https://gitee.com/api/v5/repos/";
            const string domain = "/releases?";
            var owner = RepositoryOwner;
            var repo = Repository;
            const int page = 1;
            const int perPage = 1;
            const string direction = "desc"; // 降序排列 release 版本 (首号永远为最新版)

            var requestUrlString = giteeUrl + owner + "/" + repo + domain + $"page={page}" +
                                   $"&per_page={perPage}" + $"&direction={direction}";

            try
            {
                var requestUrl = new Uri(requestUrlString);
                using (var client = new HttpClient())
                {
                    var response = await client.GetAsync(requestUrl);
                    response.EnsureSuccessStatusCode();
                    var responseBody = await response.Content.ReadAsStringAsync();

                    var releases = JArray.Parse(responseBody);

                    if (releases.Count <= 0) return null;

                    var firstRelease = releases[0];
                    var releaseId = firstRelease["id"]?.Value<int>();
                    return releaseId;
                }
            }
            catch (Exception e)
            {
                Log.Error($"从 Gitee 获取发行版ID失败: {e.Message}");
                Console.WriteLine($"从 Gitee 获取发行版ID失败: {e.Message}");
                return null;
            }
        }

        /// <summary>
        /// 根据安装包的下载地址 (url) 进行文件下载, 并通过 Toast 通知实时显示进度
        /// </summary>
        /// <returns>安装包在磁盘中的文件路径</returns>
        private static async Task<string> DownloadFileAsync()
        {
            var downloadDirectory = AppDomain.CurrentDomain.BaseDirectory + "update/";
            const string tag = "file-download";
            const string group = "downloads";
            try
            {
                // 创建目录
                if (!Directory.Exists(downloadDirectory))
                {
                    Directory.CreateDirectory(downloadDirectory);
                }

                // 检查是否有现成的安装包
                var existingFilePath = CheckFileExist(downloadDirectory);
                if (existingFilePath != null)
                {
                    return existingFilePath;
                }

                string filePath;
                using (var response = await _downloadUrl.SendAsync(HttpMethod.Get, null,
                           HttpCompletionOption.ResponseHeadersRead))
                {
                    response.ResponseMessage.EnsureSuccessStatusCode();

                    var contentLength = response.ResponseMessage.Content.Headers.ContentLength ?? 0;
                    if (contentLength == 0)
                    {
                        throw new Exception("无法获取文件大小, 取消下载");
                    }

                    var fileName = GetFileName(response.ResponseMessage.Content);
                    filePath = Path.Combine(downloadDirectory, fileName);

                    ShowProgressNotification(tag, group, 0, "准备下载更新包...");

                    Log.Info("开始下载安装包..." + _downloadUrl);
                    Console.WriteLine("开始下载安装包..." + _downloadUrl);

                    using (var inputStream = await response.GetStreamAsync())
                    using (var outputStream =
                           new FileStream(filePath, System.IO.FileMode.Create, FileAccess.Write, FileShare.None))
                    {
                        var buffer = new byte[8192];
                        long totalRead = 0;
                        int bytesRead;

                        // 按块读取数据
                        while ((bytesRead = await inputStream.ReadAsync(buffer, 0, buffer.Length)) > 0)
                        {
                            outputStream.Write(buffer, 0, bytesRead);
                            totalRead += bytesRead;

                            // 计算进度
                            var progress = (double)totalRead / contentLength;

                            // 更新 Toast 通知
                            UpdateToastNotification(tag, group, progress, $"下载中: {progress:P0}");
                        }
                    }
                }

                await Task.Delay(500); // 延迟 500ms 确保动画显示完整
                UpdateToastNotification(tag, group, 1.0, "下载完成");
                return filePath;
            }
            catch (FlurlHttpTimeoutException)
            {
                Console.WriteLine("请求超时, 请检查网络连接。");
                ShowToastNotification("下载超时, 请稍后重试");
                return null;
            }
            catch (Exception ex)
            {
                Log.Error($"下载安装包时发生异常: {ex.Message}");
                Console.WriteLine($"下载安装包时发生异常: {ex.Message}" + ex.StackTrace);
                ShowToastNotification("下载更新包失败, 请稍后再重试");
                return null;
            }
        }

        /// <summary>
        /// 检查目录中是否已经存在有最新版本的安装包
        /// </summary>
        /// <param name="downloadDirectory">安装包所在的文件目录</param>
        /// <returns>不为 null 时说明安装包已存在, 直接返回安装包的地址</returns>
        private static string CheckFileExist(string downloadDirectory)
        {
            // 去除版本号的前缀
            var latestVersion = _latestVersion.ToLower()
                .Replace("v", "").Replace("ver", "");

            // 获取安装包目录下的所有 exe 文件
            var exeFiles = Directory.GetFiles(downloadDirectory, "*.exe");

            // 比对最新版本号与现有的 exe 文件名称
            var matchingFiles = exeFiles
                .Where(file => Path.GetFileNameWithoutExtension(file).Contains(latestVersion))
                .ToList();

            // 只找到一个结果时说明安装包已经存在
            if (matchingFiles.Count == 1)
            {
                return matchingFiles.First(); // 返回现有安装包的路径
            }

            // 找到有多个符合条件的结果说明有干扰性的 exe 文件存在, 全删了重新下
            foreach (var file in exeFiles)
            {
                File.Delete(file);
            }

            return null;
        }

        /// <summary>
        /// 下载安装包时获取安装包的实际文件名
        /// </summary>
        /// <param name="content">HttpContent 参数</param>
        /// <returns>安装包的文件名, 获取失败时默认返回 "update.exe"</returns>
        private static string GetFileName(HttpContent content)
        {
            string fileName;
            if (content?.Headers?.ContentDisposition != null)
            {
                var contentDisposition = content.Headers.ContentDisposition.ToString();

                const string fileNamePattern =
                    @"filename\*=UTF-8''(?<fileName>[^;]+)|filename=""(?<fileName>[^""]+)""|filename=(?<fileName>[^;]+)";
                var match = Regex.Match(contentDisposition, fileNamePattern);

                if (match.Success)
                {
                    // 提取并处理文件名
                    fileName = match.Groups["fileName"].Value;
                    if (contentDisposition.Contains("filename*="))
                    {
                        fileName = Uri.UnescapeDataString(fileName);
                    }
                }
                else
                {
                    fileName = "update.exe"; // 无法匹配时, 使用默认文件名
                }
            }
            else
            {
                fileName = "update.exe";
            }

            return fileName;
        }

        /** 下载更新包时显示包含初始进度条的 Toast 弹窗 */
        private static void ShowProgressNotification(string tag, string group, double progress, string status)
        {
            Application.Current.Dispatcher.Invoke(() =>
            {
                var content = new ToastContentBuilder()
                    .AddText("下载更新包")
                    .AddVisualChild(new AdaptiveProgressBar
                    {
                        Title = "更新下载",
                        Value = new BindableProgressBarValue("progressValue"),
                        ValueStringOverride = new BindableString("progressValueString"),
                        Status = new BindableString("progressStatus")
                    });

                // 设置进度条初始值
                var toast = new ToastNotification(content.GetToastContent().GetXml())
                {
                    Tag = tag,
                    Group = group,
                    Data = new NotificationData
                    {
                        Values =
                        {
                            ["progressValue"] = progress.ToString(CultureInfo.CurrentCulture),
                            ["progressValueString"] = $"{progress:P0}",
                            ["progressStatus"] = status
                        },
                        SequenceNumber = 1
                    }
                };

                ToastNotificationManagerCompat.CreateToastNotifier().Show(toast);
            });
        }

        /** 根据更新包的下载进度实时更新 Toast 弹窗里的进度条 */
        private static void UpdateToastNotification(string tag, string group, double progress, string status)
        {
            // 更新 Toast 的数据
            var data = new NotificationData
            {
                SequenceNumber = 1,
                Values =
                {
                    ["progressValue"] = progress.ToString(CultureInfo.CurrentCulture),
                    ["progressValueString"] = $"{progress:P0}",
                    ["progressStatus"] = status
                }
            };

            // 更新通知
            Application.Current.Dispatcher.Invoke(() =>
            {
                var notifier = ToastNotificationManagerCompat.CreateToastNotifier();
                notifier.Update(data, tag, group);

                // 进度条达到 100% 的时候自动移除 Toast 弹窗
                if (!(Math.Abs(progress - 1.0) < 0.0001)) return;
                notifier.Update(data, tag, group);
                Thread.Sleep(2000);
                ToastNotificationManagerCompat.History.Clear();
            });
        }

        /** 请求更新弹窗 */
        private static void ShowUpdateNotification(string latestVersion)
        {
            Application.Current.Dispatcher.Invoke(() =>
            {
                var toastBuilder = new ToastContentBuilder()
                    .AddText("已经检测到新版本, 确定要更新吗?")
                    .AddText($"更新版本: {latestVersion}")
                    .AddText($"当前版本: {CurrentVersion}")
                    .AddButton(new ToastButton()
                        .SetContent("确定更新")
                        .AddArgument("action", "update")
                        .AddArgument("source", "UpdateHandler")
                    )
                    .AddButton(new ToastButton()
                        .SetContent("暂不更新")
                        .AddArgument("action", "cancelUpdate")
                        .AddArgument("source", "UpdateHandler")
                    );

                toastBuilder.Show();
            });
        }

        private static void ShowToastNotification(string message)
        {
            Application.Current.Dispatcher.Invoke(() =>
            {
                var toastBuilder = new ToastContentBuilder()
                    .AddText(message);
                toastBuilder.Show();
            });
        }

        /** 安装更新包 */
        private static void StartInstaller(string filePath)
        {
            try
            {
                var installProcess = new Process();
                installProcess.StartInfo.FileName = filePath;

                installProcess.Start();

                Console.WriteLine("已进行版本更新");
                Log.Info("已进行版本更新");
            }
            catch (Exception ex)
            {
                Log.Error("安装更新包时发生异常: " + ex.Message);
                Console.WriteLine("安装更新包时发生异常: " + ex.Message);
            }
        }
    }
}