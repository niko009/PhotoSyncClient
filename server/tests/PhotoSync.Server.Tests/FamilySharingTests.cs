using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using PhotoSync.Server.Contracts;
using PhotoSync.Server.Data;
using PhotoSync.Server.Security;
using Xunit;

namespace PhotoSync.Server.Tests;

public sealed class FamilySharingTests
{
    [Fact]
    public async Task InviteIsEmailBoundHashedOneTime_AndOnlyOwnerCanInvite()
    {
        await using var factory = new TestPhotoSyncFactory(
            new Dictionary<string, string?> { ["Portal:PublicOrigin"] = "https://photosync.test" },
            new FamilyGoogleVerifier());
        using var owner = factory.CreateClient();
        using var member = factory.CreateClient();
        var (_, ownerUuid) = await RegisterAndSignIn(owner, "owner-signin");
        await RegisterAndSignIn(member, "member-signin");

        Assert.Equal(HttpStatusCode.BadRequest,
            (await owner.PostAsJsonAsync("/api/family/invites", new CreateFamilyInviteRequest("not-an-email"))).StatusCode);

        var created = await owner.PostAsJsonAsync("/api/family/invites", new CreateFamilyInviteRequest("Member@Example.Test"));
        created.EnsureSuccessStatusCode();
        var invite = (await created.Content.ReadFromJsonAsync<FamilyInviteResponse>())!;
        Assert.Equal("member@example.test", invite.ExpectedEmail);
        Assert.StartsWith("https://photosync.test/join/", invite.InviteUrl);
        var rawToken = invite.InviteUrl!.Split('/').Last();

        await using (var scope = factory.Services.CreateAsyncScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<PhotoSyncDbContext>();
            var stored = await db.FamilyInvitations.SingleAsync();
            Assert.NotEqual(rawToken, stored.TokenHash);
            Assert.Equal(FamilyEndpoints.HashToken(rawToken), stored.TokenHash);
        }

        var wrong = await member.PostAsJsonAsync($"/api/family/join/{rawToken}", new AcceptFamilyInviteRequest("wrong-account"));
        Assert.Equal(HttpStatusCode.BadRequest, wrong.StatusCode);

        var accepted = await member.PostAsJsonAsync($"/api/family/join/{rawToken}", new AcceptFamilyInviteRequest("member-accept"));
        accepted.EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.Conflict,
            (await member.PostAsJsonAsync($"/api/family/join/{rawToken}", new AcceptFamilyInviteRequest("member-accept"))).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden,
            (await member.PostAsJsonAsync("/api/family/invites", new CreateFamilyInviteRequest("other@example.test"))).StatusCode);

        var family = await owner.GetFromJsonAsync<FamilyResponse>("/api/family");
        Assert.Equal(2, family!.Members.Count);
        Assert.Single(family.Members, x => !x.IsCurrentUser && x.Email == "member@example.test");
        Assert.Empty(family.PendingInvites);

        var albumResponse = await owner.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(ownerUuid, "Family album"));
        albumResponse.EnsureSuccessStatusCode();
        var album = (await albumResponse.Content.ReadFromJsonAsync<CreateAlbumResponse>())!;
        (await owner.PutAsJsonAsync($"/api/albums/{album.AlbumId}/sharing",
            new UpdateAlbumSharingRequest("WholeFamily", "View", null))).EnsureSuccessStatusCode();

        var ownerSharing = await owner.GetFromJsonAsync<AlbumSharingResponse>($"/api/albums/{album.AlbumId}/sharing");
        Assert.NotNull(ownerSharing);
        Assert.Equal("WholeFamily", ownerSharing!.Mode);
        Assert.Equal("View", ownerSharing.FamilyPermission);
        Assert.Empty(ownerSharing.SelectedPeople);
        Assert.Equal(HttpStatusCode.NotFound, (await member.GetAsync($"/api/albums/{album.AlbumId}/sharing")).StatusCode);

        var visibleAlbums = await member.GetFromJsonAsync<AccessibleAlbumsResponse>("/api/albums/accessible");
        Assert.Contains(visibleAlbums!.Albums, x => x.AlbumId == album.AlbumId && x.Permission == "View");
        using (var deniedUpload = Upload(ownerUuid, "Family album", "member-photo", album.AlbumId))
            Assert.Equal(HttpStatusCode.NotFound, (await member.PostAsync("/api/files/upload", deniedUpload)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound,
            (await member.PutAsJsonAsync($"/api/albums/{album.AlbumId}/sharing",
                new UpdateAlbumSharingRequest("WholeFamily", "Contribute", null))).StatusCode);

        (await owner.PutAsJsonAsync($"/api/albums/{album.AlbumId}/sharing",
            new UpdateAlbumSharingRequest("WholeFamily", "Contribute", null))).EnsureSuccessStatusCode();
        UploadFileResponse uploaded;
        using (var allowedUpload = Upload(ownerUuid, "Family album", "member-photo", album.AlbumId))
        {
            var response = await member.PostAsync("/api/files/upload", allowedUpload);
            response.EnsureSuccessStatusCode();
            uploaded = (await response.Content.ReadFromJsonAsync<UploadFileResponse>())!;
        }

        var absolute = Path.Combine(factory.StoragePath, uploaded.RelativePath.Replace('/', Path.DirectorySeparatorChar));
        Assert.True(File.Exists(absolute));

        var memberId = family.Members.Single(x => x.Email == "member@example.test").UserId;
        (await owner.DeleteAsync($"/api/family/members/{memberId}")).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.NotFound, (await member.GetAsync($"/api/files/{uploaded.ServerFileId}/download")).StatusCode);
        Assert.True(File.Exists(absolute));

        var archived = await owner.PostAsync($"/api/files/{uploaded.ServerFileId}/archive", null);
        archived.EnsureSuccessStatusCode();
        Assert.True(File.Exists(absolute));
        var folderArchived = await owner.PostAsync($"/api/albums/{album.AlbumId}/archive", null);
        folderArchived.EnsureSuccessStatusCode();
        Assert.True(File.Exists(absolute));
    }

    [Fact]
    public async Task SelectedPeopleRequiresActiveFamilyMembership_AndRevocationIsImmediate()
    {
        await using var factory = new TestPhotoSyncFactory(googleVerifier: new FamilyGoogleVerifier());
        using var owner = factory.CreateClient();
        using var member = factory.CreateClient();
        var (_, ownerUuid) = await RegisterAndSignIn(owner, "owner-signin");
        await RegisterAndSignIn(member, "member-signin");

        var inviteResponse = await owner.PostAsJsonAsync("/api/family/invites", new CreateFamilyInviteRequest("member@example.test"));
        var invite = (await inviteResponse.Content.ReadFromJsonAsync<FamilyInviteResponse>())!;
        var token = invite.InviteUrl!.Split('/').Last();
        (await member.PostAsJsonAsync($"/api/family/join/{token}", new AcceptFamilyInviteRequest("member-accept"))).EnsureSuccessStatusCode();
        var family = (await owner.GetFromJsonAsync<FamilyResponse>("/api/family"))!;
        var memberId = family.Members.Single(x => x.Email == "member@example.test").UserId;

        var created = await owner.PostAsJsonAsync("/api/albums", new CreateAlbumRequest(ownerUuid, "Selected"));
        var albumId = (await created.Content.ReadFromJsonAsync<CreateAlbumResponse>())!.AlbumId;
        (await owner.PutAsJsonAsync($"/api/albums/{albumId}/sharing",
            new UpdateAlbumSharingRequest("SelectedPeople", null, new Dictionary<int, string> { [memberId] = "View" }))).EnsureSuccessStatusCode();

        var sharing = await owner.GetFromJsonAsync<AlbumSharingResponse>($"/api/albums/{albumId}/sharing");
        Assert.NotNull(sharing);
        Assert.Equal("SelectedPeople", sharing!.Mode);
        Assert.Equal("View", sharing.SelectedPeople[memberId]);

        var visibleAlbums = await member.GetFromJsonAsync<AccessibleAlbumsResponse>("/api/albums/accessible");
        Assert.Contains(visibleAlbums!.Albums, x => x.AlbumId == albumId && x.Permission == "View");

        (await owner.DeleteAsync($"/api/family/members/{memberId}")).EnsureSuccessStatusCode();
        var albumsAfterRemoval = await member.GetFromJsonAsync<AccessibleAlbumsResponse>("/api/albums/accessible");
        Assert.DoesNotContain(albumsAfterRemoval!.Albums, x => x.AlbumId == albumId);
    }

    private static async Task<(int Id, Guid Uuid)> RegisterAndSignIn(HttpClient client, string googleToken)
    {
        var uuid = Guid.NewGuid();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", Convert.ToHexString(RandomNumberGenerator.GetBytes(32)));
        client.DefaultRequestHeaders.Add("X-PhotoSync-Device", uuid.ToString());
        var registered = await client.PostAsJsonAsync("/api/devices/register", new RegisterDeviceRequest(uuid, "Phone", "0.4.0"));
        registered.EnsureSuccessStatusCode();
        var id = (await registered.Content.ReadFromJsonAsync<RegisterDeviceResponse>())!.DeviceId;
        (await client.PostAsJsonAsync("/api/auth/google/sign-in", new { id_token = googleToken })).EnsureSuccessStatusCode();
        return (id, uuid);
    }

    private static MultipartFormDataContent Upload(Guid targetUuid, string album, string content, int? targetAlbumId = null)
    {
        var bytes = Encoding.UTF8.GetBytes(content);
        var form = new MultipartFormDataContent
        {
            { new StringContent("photo.jpg"), "original_name" },
            { new StringContent("image/jpeg"), "mime_type" },
            { new StringContent(bytes.Length.ToString()), "size_bytes" },
            { new StringContent(Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant()), "sha256" },
            { new StringContent("2026-09-01T12:00:00Z"), "created_at" },
            { new ByteArrayContent(bytes), "file", "photo.jpg" }
        };
        if (targetAlbumId is int albumId)
        {
            form.Add(new StringContent(albumId.ToString()), "album_id");
        }
        else
        {
            form.Add(new StringContent(targetUuid.ToString()), "device_uuid");
            form.Add(new StringContent(album), "album_name");
        }
        return form;
    }

    private sealed class FamilyGoogleVerifier : IGoogleTokenVerifier
    {
        public Task<VerifiedGoogleIdentity?> VerifyAsync(string idToken, CancellationToken cancellationToken)
        {
            VerifiedGoogleIdentity? identity = idToken switch
            {
                "owner-signin" => new("google-owner", "owner@example.test", "Owner"),
                "member-signin" or "member-accept" => new("google-member", "member@example.test", "Member"),
                "wrong-account" => new("google-member", "wrong@example.test", "Wrong account"),
                _ => null
            };
            return Task.FromResult(identity);
        }
    }
}
