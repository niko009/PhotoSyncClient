using Microsoft.AspNetCore.Mvc;

namespace PhotoSync.Server.Endpoints;

public static class ApiProblems
{
    public static ProblemDetails Validation(string code, string message)
        => Create(code, message, StatusCodes.Status400BadRequest);

    public static ProblemDetails NotFound(string code, string message)
        => Create(code, message, StatusCodes.Status404NotFound);

    public static ProblemDetails Create(string code, string message, int statusCode)
    {
        var details = new ProblemDetails
        {
            Status = statusCode,
            Title = code,
            Detail = message
        };
        details.Extensions["code"] = code;
        return details;
    }
}
