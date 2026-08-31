using PhotoSync.Server.Portal;

namespace PhotoSync.Server.Endpoints;

public static class AdminEndpoints
{
    public static IEndpointRouteBuilder MapAdminEndpoints(this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapGet("/admin", () => Results.Redirect("/portal/")).RequireAuthorization(PortalSetup.AdminPolicy);
        endpoints.MapGet("/api/admin/dashboard", PortalEndpoints.AdminDashboardAsync).RequireAuthorization(PortalSetup.AdminPolicy);
        return endpoints;
    }
}
