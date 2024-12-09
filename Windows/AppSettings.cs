namespace WinCAPTCHA;

public class AppSettings
{
    private readonly string? _appName;

    public AppSettings(string? appName = null)
    {
        _appName = string.IsNullOrWhiteSpace(appName) ? null : appName;
    }

    public string AppName => string.IsNullOrWhiteSpace(_appName) ? "默认值" : _appName;
}