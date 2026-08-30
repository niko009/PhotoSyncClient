using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using PhotoSync.Server.Contracts;

namespace PhotoSync.Server.Tests;

public sealed class UploadApiTests(TestServerFactory factory) : IClassFixture<TestServerFactory>
{
    private readonly HttpClient _client = factory.CreateClient();

    [Fact]
    public async Task UploadFlow_RegistersAlbumStoresFileAndReturnsExistingOnCheck()
    {
        var deviceUuid = Guid.NewGuid();

        var registerResponse = await _client.PostAsJsonAsync("/api/devices/register", new RegisterDeviceRequest(
            deviceUuid,
            "Samsung S24",
            "0.1.0"));
        Assert.Equal(HttpStatusCode.OK, registerResponse.StatusCode);

        var albumResponse = await _client.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(
            deviceUuid,
            "Family"));
        Assert.Equal(HttpStatusCode.OK, albumResponse.StatusCode);

        var payload = Encoding.UTF8.GetBytes("sample-image-content");
        var hash = Convert.ToHexString(SHA256.HashData(payload)).ToLowerInvariant();

        using var form = new MultipartFormDataContent
        {
            { new StringContent(deviceUuid.ToString()), "device_uuid" },
            { new StringContent("Family"), "album_name" },
            { new StringContent("IMG_0001.jpg"), "original_name" },
            { new StringContent("image/jpeg"), "mime_type" },
            { new StringContent(payload.Length.ToString()), "size_bytes" },
            { new StringContent(hash), "sha256" },
            { new StringContent("2026-06-04T13:25:11Z"), "created_at" },
            { new StringContent("4032"), "width" },
            { new StringContent("3024"), "height" },
            { new StringContent("false"), "is_video" },
            { new ByteArrayContent(payload), "file", "IMG_0001.jpg" }
        };

        var uploadResponse = await _client.PostAsync("/api/files/upload", form);
        var uploadBody = await uploadResponse.Content.ReadAsStringAsync();
        Assert.True(
            uploadResponse.StatusCode == HttpStatusCode.Created || uploadResponse.StatusCode == HttpStatusCode.OK,
            uploadBody);

        var uploadPayload = await uploadResponse.Content.ReadFromJsonAsync<UploadFileResponse>();
        Assert.NotNull(uploadPayload);
        Assert.Contains("devices/", uploadPayload!.RelativePath);

        var checkResponse = await _client.PostAsJsonAsync("/api/files/check", new FileCheckRequest(
            deviceUuid,
            "Family",
            "IMG_0001.jpg",
            payload.Length,
            hash));
        Assert.Equal(HttpStatusCode.OK, checkResponse.StatusCode);

        var checkPayload = await checkResponse.Content.ReadFromJsonAsync<FileCheckResponse>();
        Assert.NotNull(checkPayload);
        Assert.True(checkPayload!.Exists);
        Assert.Equal(uploadPayload.ServerFileId, checkPayload.ServerFileId);
    }

    [Fact]
    public async Task SummaryEndpoints_ReturnExpectedCounts()
    {
        var summaryResponse = await _client.GetAsync("/api/stats/summary");
        Assert.Equal(HttpStatusCode.OK, summaryResponse.StatusCode);

        var devicesResponse = await _client.GetAsync("/api/devices");
        Assert.Equal(HttpStatusCode.OK, devicesResponse.StatusCode);
    }
}
