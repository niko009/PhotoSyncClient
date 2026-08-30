namespace PhotoSync.Server.Options;

public sealed class PhotoSyncOptions
{
    public const string SectionName = "PhotoSync";

    public string ServerName { get; set; } = "Home PhotoSync";

    public string StorageRoot { get; set; } = "data";

    public string TempRoot { get; set; } = Path.Combine("data", "temp");

    public string PreviewRoot { get; set; } = Path.Combine("data", "previews");

    public string DatabasePath { get; set; } = Path.Combine("data", "system", "photosync.db");
}
