using System.Text.Json.Serialization;

namespace PhotoSync.Server.Contracts;

public sealed record CreateAlbumRequest(
    [property: JsonPropertyName("device_uuid")] Guid DeviceUuid,
    [property: JsonPropertyName("album_name")] string AlbumName);

public sealed record CreateAlbumResponse(
    [property: JsonPropertyName("album_id")] int AlbumId,
    [property: JsonPropertyName("created")] bool Created,
    [property: JsonPropertyName("server_folder_path")] string ServerFolderPath);

public sealed record AlbumListItem(
    [property: JsonPropertyName("album_id")] int AlbumId,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("server_folder_path")] string ServerFolderPath);

public sealed record AlbumsResponse(
    [property: JsonPropertyName("albums")] IReadOnlyList<AlbumListItem> Albums);

public sealed record AccessibleAlbumItem(
    [property: JsonPropertyName("album_id")] int AlbumId,
    [property: JsonPropertyName("name")] string Name,
    [property: JsonPropertyName("permission")] string Permission,
    [property: JsonPropertyName("sharing_mode")] string SharingMode,
    [property: JsonPropertyName("owned_by_me")] bool OwnedByMe);

public sealed record AccessibleAlbumsResponse(
    [property: JsonPropertyName("albums")] IReadOnlyList<AccessibleAlbumItem> Albums);

public sealed record UpdateAlbumSharingRequest(
    [property: JsonPropertyName("mode")] string Mode,
    [property: JsonPropertyName("family_permission")] string? FamilyPermission,
    [property: JsonPropertyName("selected_people")] IReadOnlyDictionary<int, string>? SelectedPeople);

public sealed record AlbumSharingResponse(
    [property: JsonPropertyName("album_id")] int AlbumId,
    [property: JsonPropertyName("mode")] string Mode,
    [property: JsonPropertyName("family_permission")] string FamilyPermission,
    [property: JsonPropertyName("selected_people")] IReadOnlyDictionary<int, string> SelectedPeople);
