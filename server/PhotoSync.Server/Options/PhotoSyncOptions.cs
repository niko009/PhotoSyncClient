namespace PhotoSync.Server.Options;

public sealed class PhotoSyncOptions
{
    public const string SectionName = "PhotoSync";

    public string ServerName { get; set; } = "Home PhotoSync";

    public string StorageRoot { get; set; } = "data";

    public string TempRoot { get; set; } = Path.Combine("data", "temp");

    public string PreviewRoot { get; set; } = Path.Combine("data", "previews");

    public string DatabasePath { get; set; } = Path.Combine("data", "system", "photosync.db");
    public bool AllowDeviceRegistration { get; set; } = true;
    public int MaxDevices { get; set; } = 5;
    public long MaxFileBytes { get; set; } = 25 * 1024 * 1024;
    public long MaxStorageBytes { get; set; } = 10L * 1024 * 1024 * 1024;
    public long MinFreeDiskBytes { get; set; } = 512L * 1024 * 1024;
}
