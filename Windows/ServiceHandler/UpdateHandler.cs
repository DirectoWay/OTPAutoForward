using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Windows.Web.Http;
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
            if (string.IsNullOrWhiteSpace(App.AppSettings.ReleasesSource))
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
                    ShowToastNotification($"暂无更新\n当前版本: {App.AppSettings.CurrentVersion}");
                    return;
                }

                if (latestVersion != App.AppSettings.CurrentVersion)
                {
                    _downloadUrl = downloadUrl;
                    ShowUpdateNotification(latestVersion);
                }
                else
                {
                    ShowToastNotification($"当前版本已是最新版\n当前版本: {App.AppSettings.CurrentVersion}");
                }
            }
            catch (Exception ex)
            {
                Log.Error($"检查更新时发生异常: {ex.Message}");
                ShowToastNotification("检查更新失败，请稍后再试");
            }
        }

        /// <summary>
        /// 获取最新版本
        /// </summary>
        /// <returns>最新版本号, 安装包下载地址</returns>
        private static Task<(string, string)> GetLatestReleaseAsync()
        {
            var releaseSource = App.AppSettings.ReleasesSource.ToLower();

            if (releaseSource.Contains("gitee"))
            {
                return GetGiteeReleaseAsync();
            }

            if (releaseSource.Contains("github"))
            {
                return GetGitHubReleaseAsync();
            }

            ShowToastNotification("暂无更新");

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

                var latestVersion = latestRelease.TagName;
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

                    var latestVersion = releaseObject["tag_name"]?.ToString();
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
                throw;
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
        /// 根据安装包的下载地址 (url) 进行文件下载
        /// </summary>
        /// <returns>安装包在磁盘中的文件路径</returns>
        private static async Task<string> DownloadFileAsync()
        {
            var downloadDirectory = AppDomain.CurrentDomain.BaseDirectory + "update/";
            var filePath = downloadDirectory + "update.exe";

            try
            {
                // 创建目录
                if (!Directory.Exists(_downloadUrl))
                {
                    Directory.CreateDirectory(downloadDirectory);
                }

                Log.Info("开始下载安装包..." + _downloadUrl);
                Console.WriteLine("开始下载安装包..." + _downloadUrl);
                await _downloadUrl.DownloadFileAsync(downloadDirectory, "update.exe");
                return filePath;
            }
            catch (Exception ex)
            {
                Log.Error($"下载安装包时发生异常: {ex.Message}");
                Console.WriteLine($"下载安装包时发生异常: {ex.Message}");
                ShowToastNotification("下载更新包失败, 请稍后再重试");
                return null;
            }
        }

        /** 请求更新弹窗 */
        private static void ShowUpdateNotification(string latestVersion)
        {
            Application.Current.Dispatcher.Invoke(() =>
            {
                var toastBuilder = new ToastContentBuilder()
                    .AddText("已经检测到新版本, 确定要更新吗?")
                    .AddText($"更新版本: {latestVersion}")
                    .AddText($"当前版本: {App.AppSettings.CurrentVersion}")
                    .AddButton(new ToastButton()
                        .SetContent("确定更新")
                        .AddArgument("action", "update")
                    )
                    .AddButton(new ToastButton()
                        .SetContent("暂不更新")
                        .AddArgument("action", "cancelUpdate")
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